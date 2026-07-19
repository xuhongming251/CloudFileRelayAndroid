package com.cloudfilerelay.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TaskListInteractionTest {
    @Test
    public void taskBadgeCountsOnlyActiveTasks() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        removeBadgeTestTasks(target);
        int baseline = activeTaskCount(target);
        TaskItem first = TaskStore.add(target, "badge_active_one.safetensors",
                "Civitai", "quark", "1 MB", "");
        TaskItem second = TaskStore.add(target, "badge_active_two.safetensors",
                "Hugging Face", "quark", "2 MB", "");
        TaskItem completed = TaskStore.add(target, "badge_completed.safetensors",
                "Civitai", "quark", "3 MB", "");
        completed.status = TaskItem.COMPLETED;
        completed.progress = 100;
        TaskStore.upsert(target, completed);

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try {
            restartMainActivity(device);
            int expected = baseline + 2;
            UiObject2 badge = device.wait(Until.findObject(
                    By.res("com.cloudfilerelay.app", "task_badge")), 4_000);
            assertNotNull(badge);
            assertTrue((expected > 99 ? "99+" : String.valueOf(expected)).equals(badge.getText()));
            assertTrue(("正在进行 " + expected + " 个任务").equals(badge.getContentDescription()));

            second.status = TaskItem.COMPLETED;
            second.progress = 100;
            TaskStore.upsert(target, second);
            restartMainActivity(device);
            int updated = baseline + 1;
            UiObject2 updatedBadge = device.wait(Until.findObject(
                    By.res("com.cloudfilerelay.app", "task_badge")), 4_000);
            assertNotNull(updatedBadge);
            assertTrue((updated > 99 ? "99+" : String.valueOf(updated)).equals(updatedBadge.getText()));
        } finally {
            removeBadgeTestTasks(target);
            restartMainActivity(device);
        }
    }

    @Test
    public void clearConfirmationAndSwipeDeleteAreReachable() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TaskItem testTask = TaskStore.add(target, "swipe_delete_test.safetensors",
                "Hugging Face", "quark", "1 MB", "");
        testTask.status = TaskItem.COMPLETED;
        testTask.progress = 100;
        testTask.shareUrl = "https://example.com/test";
        TaskStore.upsert(target, testTask);
        String filename = testTask.filename;

        try {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.wakeUp();
            device.executeShellCommand("am start -W -n com.cloudfilerelay.app/com.cloudfilerelay.app.MainActivity --es tab tasks");
            assertTrue(device.wait(Until.hasObject(By.text("转存任务")), 4_000));

            UiObject2 filenameView = device.findObject(By.text(filename));
            assertNotNull(filenameView);
            Rect before = new Rect(filenameView.getVisibleBounds());
            int y = before.centerY();
            device.swipe(device.getDisplayWidth() * 4 / 5, y,
                    device.getDisplayWidth() * 2 / 5, y, 18);

            Thread.sleep(450);
            UiObject2 shiftedFilename = device.findObject(By.text(filename));
            assertNotNull(shiftedFilename);
            Rect after = shiftedFilename.getVisibleBounds();
            assertTrue("左滑后任务卡片应向左移动", after.left < before.left - 40);
            assertNotNull(device.findObject(By.text("删除")));
            device.executeShellCommand("screencap -p /sdcard/task_swipe_delete_verified.png");

            UiObject2 clear = device.findObject(By.text("清空"));
            assertNotNull(clear);
            clear.click();
            assertTrue(device.wait(Until.hasObject(By.text("清空任务列表？")), 2_000));
            UiObject2 cancel = device.findObject(By.text("取消"));
            assertNotNull(cancel);
            cancel.click();
            assertTrue(device.wait(Until.gone(By.text("清空任务列表？")), 2_000));
        } finally {
            TaskStore.remove(target, testTask.id);
        }
    }

    @Test
    public void tappingLongFilenameStartsFullNameMarquee() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String filename = "this_is_a_very_long_complete_model_filename_for_marquee_verification"
                + "_fp16_pruned.safetensors";
        TaskItem testTask = TaskStore.add(target, filename,
                "Hugging Face", "quark", "1.83 GB", "");

        try {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.wakeUp();
            device.executeShellCommand("am start -W -n "
                    + "com.cloudfilerelay.app/com.cloudfilerelay.app.MainActivity --es tab tasks");
            assertTrue(device.wait(Until.hasObject(By.text("转存任务")), 4_000));

            UiObject2 filenameView = device.findObject(By.text(filename));
            assertNotNull(filenameView);
            filenameView.click();
            Thread.sleep(500);

            UiObject2 scrolling = device.findObject(
                    By.desc("正在滚动完整文件名：" + filename));
            assertNotNull("点击任务后应进入完整文件名滚动状态", scrolling);
            assertTrue("滚动中的文件名应保持完整文本", filename.equals(scrolling.getText()));
            assertTrue("滚动中的文件名应处于 selected 状态", scrolling.isSelected());
        } finally {
            TaskStore.remove(target, testTask.id);
        }
    }

    private static int activeTaskCount(Context context) {
        int count = 0;
        for (TaskItem item : TaskStore.load(context)) {
            if (!TaskItem.COMPLETED.equals(item.status)
                    && !TaskItem.FAILED.equals(item.status)
                    && !TaskItem.SERVICE_REQUIRED.equals(item.status)) count++;
        }
        return count;
    }

    private static void restartMainActivity(UiDevice device) throws Exception {
        device.pressHome();
        Thread.sleep(250);
        device.executeShellCommand("am start -W -n "
                + "com.cloudfilerelay.app/com.cloudfilerelay.app.MainActivity");
        assertTrue(device.wait(Until.hasObject(By.text("云端转存")), 4_000));
    }

    private static void removeBadgeTestTasks(Context context) {
        for (TaskItem item : TaskStore.load(context)) {
            if (item.filename.startsWith("badge_")) TaskStore.remove(context, item.id);
        }
    }
}
