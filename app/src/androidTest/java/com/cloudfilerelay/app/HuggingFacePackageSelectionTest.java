package com.cloudfilerelay.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class HuggingFacePackageSelectionTest {
    @Test
    public void parsesPackageOptionSeparatelyAndKeepsItAfterModelFiles() {
        String json = "{\"files\":["
                + "{\"name\":\"repository.zip\",\"url\":\"https://huggingface.co/owner/repository\",\"packageAll\":true},"
                + "{\"name\":\"model.safetensors\",\"url\":\"https://huggingface.co/owner/repository/resolve/main/model.safetensors?download=true\",\"size\":\"2 GB\"}"
                + "]}";

        List<PageDetector.FileCandidate> files = PageDetector.parse(json);

        assertEquals(2, files.size());
        assertEquals("model.safetensors", files.get(0).name);
        assertFalse(files.get(0).packageAll);
        assertEquals("repository.zip", files.get(1).name);
        assertEquals("https://huggingface.co/owner/repository", files.get(1).url);
        assertTrue(files.get(1).packageAll);
    }
}
