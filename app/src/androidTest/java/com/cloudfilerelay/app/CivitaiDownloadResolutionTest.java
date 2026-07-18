package com.cloudfilerelay.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CivitaiDownloadResolutionTest {
    private static final String SOURCE =
            "https://civitai.com/api/download/models/3077110";

    @Test
    public void rejectsAuthenticationAndHtmlResponses() {
        assertTrue(GeckoBridge.isInvalidDownloadResult(
                SOURCE,
                "https://auth.civitai.com/login?reason=download-auth",
                "text/html; charset=utf-8"));
        assertTrue(GeckoBridge.isInvalidDownloadResult(
                SOURCE,
                SOURCE,
                "application/octet-stream"));
    }

    @Test
    public void acceptsSignedCivitaiBinaryUrls() {
        assertFalse(GeckoBridge.isInvalidDownloadResult(
                SOURCE,
                "https://b2.civitai.com/file/civitai-modelfiles/model/1711276/model.safetensors"
                        + "?Authorization=signed",
                "binary/octet-stream"));
        assertFalse(GeckoBridge.isInvalidDownloadResult(
                SOURCE,
                "https://civitai-delivery-worker-prod.example.r2.cloudflarestorage.com/"
                        + "model/4313379/model.safetensors?X-Amz-Signature=signed",
                "application/octet-stream"));
    }

    @Test
    public void keepsCanonicalHuggingFaceFileUrlAfterXetProbe() {
        String source = "https://huggingface.co/conradlocke/krea2-identity-edit/"
                + "resolve/main/krea2_identity_edit_v1.safetensors?download=true";
        String rangeOnlyProbe = "https://us.aws.cdn.hf.co/xet-bridge-us/repo/hash"
                + "?Policy=range-bytes-0-0&Signature=signed";
        assertEquals(source, GeckoBridge.canonicalDownloadUrl(source, rangeOnlyProbe));
        assertFalse(GeckoBridge.isInvalidDownloadResult(
                source,
                GeckoBridge.canonicalDownloadUrl(source, rangeOnlyProbe),
                "binary/octet-stream"));
    }
}
