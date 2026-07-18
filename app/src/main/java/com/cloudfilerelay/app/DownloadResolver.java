package com.cloudfilerelay.app;

import android.net.Uri;

final class DownloadResolver {
    interface Callback {
        void onResolved(String finalUrl);
        void onError(String message);
    }

    private DownloadResolver() {}

    static void resolve(org.mozilla.geckoview.GeckoSession session, String sourceUrl,
                        Callback callback) {
        try {
            validateInitialUrl(sourceUrl);
            GeckoBridge.resolve(session, sourceUrl, callback);
        } catch (Exception error) {
            callback.onError(error.getMessage() == null ? "无法获取下载地址" : error.getMessage());
        }
    }

    private static void validateInitialUrl(String url) throws Exception {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) throw new Exception("下载地址无效");
        boolean secure = "https".equalsIgnoreCase(scheme);
        boolean provider = host.equals("civitai.com") || host.endsWith(".civitai.com")
                || host.equals("huggingface.co") || host.endsWith(".huggingface.co");
        boolean localDebug = BuildConfig.DEBUG && "http".equalsIgnoreCase(scheme)
                && (host.equals("10.0.2.2") || host.equals("127.0.0.1") || host.equals("localhost"));
        if ((!secure || !provider) && !localDebug) {
            throw new Exception("仅支持 Civitai 和 Hugging Face 下载地址");
        }
    }
}
