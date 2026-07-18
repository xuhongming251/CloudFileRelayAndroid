package com.cloudfilerelay.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TaskStatusTest {
    @Test
    public void legacyQueuedTaskContinuesAsFetching() throws Exception {
        JSONObject saved = new JSONObject()
                .put("id", "legacy-task")
                .put("status", "queued")
                .put("progress", 3);

        TaskItem item = TaskItem.fromJson(saved);

        assertEquals(TaskItem.FETCHING, item.status);
        assertEquals(3, item.progress);
        assertEquals("获取中", item.statusName());
        assertFalse(item.statusName().contains("排队"));
    }
}
