package com.cloudfilerelay.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ModelCatalogLogicTest {
    @Test
    public void parserKeepsOnlyModelFormats() throws Exception {
        String json = "{\"updatedAt\":\"2026-07-18T00:00:00Z\",\"items\":["
                + "{\"filename\":\"a.safetensors\",\"share_url\":\"https://example.com/a\"},"
                + "{\"filename\":\"b.gguf\",\"share_url\":\"https://example.com/b\"},"
                + "{\"filename\":\"c.onnx\",\"share_url\":\"https://example.com/c\"},"
                + "{\"filename\":\"archive.zip\",\"share_url\":\"https://example.com/d\"},"
                + "{\"filename\":\"folder-name\",\"share_url\":\"https://example.com/e\"}]}";

        ModelCatalogRepository.ParsedCatalog parsed = ModelCatalogRepository.parseCatalog(json);

        assertEquals(3, parsed.items.size());
        assertEquals("a.safetensors", parsed.items.get(0).filename);
        assertEquals("b.gguf", parsed.items.get(1).filename);
        assertEquals("c.onnx", parsed.items.get(2).filename);
    }

    @Test
    public void searchUsesSpaceSeparatedOrderIndependentAndFuzzyTokens() {
        ModelCatalogItem matching = new ModelCatalogItem(
                "Wan2.1_I2V_14B_CineScale_lora_rank16_fp16.safetensors", "", "https://example.com/1", "");
        ModelCatalogItem missingToken = new ModelCatalogItem(
                "Wan2.1_I2V_14B_CineScale_fp16.safetensors", "", "https://example.com/2", "");
        List<ModelCatalogItem> source = Arrays.asList(missingToken, matching);

        List<ModelCatalogItem> combined = ModelCatalogSearch.search(source, "fp16 wan lora");
        List<ModelCatalogItem> fuzzy = ModelCatalogSearch.search(source, "wan lra fp16");

        assertEquals(1, combined.size());
        assertEquals(matching.filename, combined.get(0).filename);
        assertEquals(1, fuzzy.size());
        assertEquals(matching.filename, fuzzy.get(0).filename);
        assertTrue(ModelCatalogSearch.tokens("  wan   lora  fp16 ").size() == 3);
    }
}
