package com.cloudfilerelay.app;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Native-messaging bridge used by the bundled Gecko extension. */
final class GeckoBridge {
    interface DetectionListener {
        void onDetection(String json);
    }

    private static final String TAG = "CloudRelayGecko";
    private static final String EXTENSION_LOCATION =
            "resource://android/assets/web_extensions/cloudfilerelay/";
    private static final String EXTENSION_ID = "cloudfilerelay@local";
    private static final String NATIVE_APP = "cloudfilerelay";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<GeckoSession, DetectionListener> sessions = new LinkedHashMap<>();
    private static final Map<GeckoSession, WebExtension.Port> contentPorts = new LinkedHashMap<>();
    private static final Map<String, PendingResolve> pendingResolves = new LinkedHashMap<>();

    private static GeckoRuntime runtime;
    private static WebExtension extension;
    private static WebExtension.Port backgroundPort;
    private static boolean installing;
    private static String installError;

    private GeckoBridge() {}

    static void initialize(GeckoRuntime value) {
        MAIN.post(() -> {
            if (runtime == value && (installing || extension != null)) return;
            runtime = value;
            installing = true;
            installError = null;
            runtime.getWebExtensionController()
                    .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
                    .accept(GeckoBridge::onExtensionReady, error -> {
                        installing = false;
                        installError = error.getMessage() == null
                                ? "浏览器下载组件初始化失败" : error.getMessage();
                        Log.e(TAG, "Unable to install built-in extension", error);
                        failAll(installError);
                    });
        });
    }

    private static void onExtensionReady(WebExtension value) {
        installing = false;
        extension = value;
        extension.setMessageDelegate(BACKGROUND_DELEGATE, NATIVE_APP);
        for (Map.Entry<GeckoSession, DetectionListener> entry : sessions.entrySet()) {
            attachMessageDelegate(entry.getKey(), entry.getValue());
        }
    }

    static void attachSession(GeckoSession session, DetectionListener listener) {
        MAIN.post(() -> {
            sessions.put(session, listener);
            if (extension != null) attachMessageDelegate(session, listener);
        });
    }

    static void detachSession(GeckoSession session) {
        MAIN.post(() -> {
            sessions.remove(session);
            WebExtension.Port port = contentPorts.remove(session);
            if (port != null) port.disconnect();
            if (extension != null) {
                session.getWebExtensionController().setMessageDelegate(extension, null, NATIVE_APP);
            }
        });
    }

    private static void attachMessageDelegate(GeckoSession session, DetectionListener listener) {
        session.getWebExtensionController().setMessageDelegate(extension,
                new WebExtension.MessageDelegate() {
                    @Override
                    public GeckoResult<Object> onMessage(String nativeApp, Object message,
                                                         WebExtension.MessageSender sender) {
                        if (!NATIVE_APP.equals(nativeApp) || sender.session != session
                                || !sender.isTopLevel()) return GeckoResult.fromValue(null);
                        JSONObject json = asJson(message);
                        if (json != null && "detection".equals(json.optString("type"))) {
                            listener.onDetection(json.toString());
                        }
                        return GeckoResult.fromValue(null);
                    }

                    @Override
                    public void onConnect(WebExtension.Port port) {
                        if (!NATIVE_APP.equals(port.name) || port.sender.session != session
                                || !port.sender.isTopLevel()) return;
                        contentPorts.put(session, port);
                        port.setDelegate(new WebExtension.PortDelegate() {
                            @Override
                            public void onPortMessage(Object message, WebExtension.Port source) {
                                handlePortMessage(message);
                            }

                            @Override
                            public void onDisconnect(WebExtension.Port source) {
                                if (contentPorts.get(session) == source) contentPorts.remove(session);
                            }
                        });
                        flushPending(session);
                    }
                }, NATIVE_APP);
    }

    static void resolve(GeckoSession session, String url, DownloadResolver.Callback callback) {
        MAIN.post(() -> {
            if (installError != null) {
                callback.onError("浏览器下载组件不可用：" + installError);
                return;
            }
            String requestId = UUID.randomUUID().toString();
            PendingResolve pending = new PendingResolve(requestId, session, url, callback);
            pending.timeout = () -> completeError(requestId, "获取下载地址超时，请重试");
            pendingResolves.put(requestId, pending);
            MAIN.postDelayed(pending.timeout, 20_000);
            send(pending);
        });
    }

    private static void send(PendingResolve pending) {
        WebExtension.Port port = contentPorts.get(pending.session);
        if (port == null) port = backgroundPort;
        if (port == null) return;
        try {
            JSONObject request = new JSONObject();
            request.put("type", "resolve");
            request.put("requestId", pending.requestId);
            request.put("url", pending.url);
            port.postMessage(request);
            pending.sent = true;
        } catch (Exception error) {
            completeError(pending.requestId, "无法请求下载地址");
        }
    }

    private static void flushPending() {
        for (PendingResolve pending : pendingResolves.values()) {
            if (!pending.sent) send(pending);
        }
    }

    private static void flushPending(GeckoSession session) {
        for (PendingResolve pending : pendingResolves.values()) {
            if (!pending.sent && pending.session == session) send(pending);
        }
    }

    private static void handlePortMessage(Object message) {
        JSONObject json = asJson(message);
        if (json == null) return;
        String type = json.optString("type");
        if ("ready".equals(type)) {
            flushPending();
        } else if ("resolved".equals(type)) {
            String requestId = json.optString("requestId");
            PendingResolve pending = pendingResolves.get(requestId);
            if (pending != null) {
                String resolvedUrl = canonicalDownloadUrl(
                        pending.url, json.optString("url", pending.url));
                String contentType = json.optString("contentType").toLowerCase(java.util.Locale.ROOT);
                if (isInvalidDownloadResult(pending.url, resolvedUrl, contentType)) {
                    completeError(requestId, "获取下载地址失败，请先登录！");
                    return;
                }
                pendingResolves.remove(requestId);
                MAIN.removeCallbacks(pending.timeout);
                pending.callback.onResolved(resolvedUrl);
            }
        } else if ("resolveError".equals(type)) {
            completeError(json.optString("requestId"),
                    json.optString("message", "无法获取下载地址"));
        }
    }

    private static void completeError(String requestId, String message) {
        PendingResolve pending = pendingResolves.remove(requestId);
        if (pending == null) return;
        MAIN.removeCallbacks(pending.timeout);
        pending.callback.onError(message);
    }

    static boolean isInvalidDownloadResult(String sourceUrl, String resolvedUrl,
                                           String contentType) {
        if (resolvedUrl == null || resolvedUrl.isEmpty()) return true;
        if (contentType.contains("text/html") || contentType.contains("application/xhtml")
                || contentType.contains("application/json")) return true;
        try {
            Uri resolved = Uri.parse(resolvedUrl);
            String host = resolved.getHost() == null ? "" : resolved.getHost().toLowerCase();
            String path = resolved.getPath() == null ? "" : resolved.getPath().toLowerCase();
            if ("auth.civitai.com".equals(host) || path.startsWith("/login")) return true;
            return sourceUrl.contains("/api/download/models/")
                    && (host.equals("civitai.com") || host.endsWith(".civitai.com"))
                    && path.contains("/api/download/models/");
        } catch (Exception ignored) {
            return true;
        }
    }

    static String canonicalDownloadUrl(String sourceUrl, String resolvedUrl) {
        try {
            Uri source = Uri.parse(sourceUrl);
            String host = source.getHost() == null ? "" : source.getHost().toLowerCase();
            String path = source.getPath() == null ? "" : source.getPath();
            if ((host.equals("huggingface.co") || host.endsWith(".huggingface.co"))
                    && path.contains("/resolve/")) {
                return sourceUrl;
            }
        } catch (Exception ignored) {}
        return resolvedUrl;
    }

    private static void failAll(String message) {
        String[] ids = pendingResolves.keySet().toArray(new String[0]);
        for (String id : ids) completeError(id, message);
    }

    private static JSONObject asJson(Object message) {
        if (message instanceof JSONObject) return (JSONObject) message;
        if (message instanceof String) {
            try { return new JSONObject((String) message); } catch (Exception ignored) {}
        }
        return null;
    }

    private static final WebExtension.MessageDelegate BACKGROUND_DELEGATE =
            new WebExtension.MessageDelegate() {
                @Override public void onConnect(WebExtension.Port port) {
                    if (!NATIVE_APP.equals(port.name)) return;
                    backgroundPort = port;
                    port.setDelegate(new WebExtension.PortDelegate() {
                        @Override public void onPortMessage(Object message, WebExtension.Port source) {
                            handlePortMessage(message);
                        }

                        @Override public void onDisconnect(WebExtension.Port source) {
                            if (backgroundPort == source) backgroundPort = null;
                        }
                    });
                    flushPending();
                }
            };

    private static final class PendingResolve {
        final String requestId;
        final GeckoSession session;
        final String url;
        final DownloadResolver.Callback callback;
        Runnable timeout;
        boolean sent;

        PendingResolve(String requestId, GeckoSession session, String url,
                       DownloadResolver.Callback callback) {
            this.requestId = requestId;
            this.session = session;
            this.url = url;
            this.callback = callback;
        }
    }
}
