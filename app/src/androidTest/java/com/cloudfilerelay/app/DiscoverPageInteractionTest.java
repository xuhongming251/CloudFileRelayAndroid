package com.cloudfilerelay.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DiscoverPageInteractionTest {
    @Test
    public void combinedSearchRendersRealTransferResults() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp();
        device.executeShellCommand("am start -W -n "
                + "com.cloudfilerelay.app.debug/com.cloudfilerelay.app.MainActivity --es tab browse");

        assertTrue(device.wait(Until.hasObject(By.text("发现模型")), 5_000));
        assertTrue(device.wait(Until.hasObject(By.textContains("个可转存模型")), 10_000));
        UiObject2 search = device.findObject(By.desc("模型搜索"));
        assertNotNull(search);
        search.setText("wan lora fp16");

        assertTrue(device.wait(Until.hasObject(By.text("找到 6 个模型")), 5_000));
        assertTrue(device.hasObject(By.text("全部匹配 · 顺序不限")));
        assertTrue(device.hasObject(By.text("按匹配度排序")));
        assertNotNull(device.findObject(By.text("转存网盘")));
        device.executeShellCommand("screencap -p /sdcard/discover_search_verified.png");
    }
}
