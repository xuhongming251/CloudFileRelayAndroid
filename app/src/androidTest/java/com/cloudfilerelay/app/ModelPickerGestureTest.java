package com.cloudfilerelay.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
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
public class ModelPickerGestureTest {
    @Test
    public void draggingTitleDownDismissesPicker() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(target, BrowserActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("start_url",
                "https://huggingface.co/openai-community/gpt2/resolve/main/config.json?download=true");
        intent.putExtra("direct_download", true);
        target.startActivity(intent);

        assertTrue(device.wait(Until.hasObject(By.text("发现可转存文件")), 12_000));
        UiObject2 viewFiles = device.findObject(By.text("查看文件"));
        assertNotNull(viewFiles);
        viewFiles.click();

        assertTrue(device.wait(Until.hasObject(By.text("选择模型文件")), 3_000));
        UiObject2 title = device.findObject(By.text("选择模型文件"));
        assertNotNull(title);
        Rect bounds = title.getVisibleBounds();
        device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(),
                Math.min(device.getDisplayHeight() - 80, bounds.centerY() + 520), 24);

        assertTrue("从标题下拉后文件选择面板应关闭",
                device.wait(Until.gone(By.text("选择模型文件")), 3_000));
    }
}
