package com.cloudfilerelay.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ModelCatalogRepository {
    static final String CATALOG_URL =
            "https://raw.githubusercontent.com/xuhongming251/modelhub/main/data/sync_cache.json";
    private static final String CACHE_FILE = "model_catalog_cache.json";
    private static final String PREFS = "model_catalog_meta";
    private static final String KEY_ETAG = "etag";
    private static final String KEY_LAST_MODIFIED = "last_modified";
    private static final String KEY_LAST_CHECKED = "last_checked";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Set<String> MODEL_EXTENSIONS = new HashSet<>();
    private static volatile ModelCatalogRepository instance;

    static {
        Collections.addAll(MODEL_EXTENSIONS, "pb", "h5", "ckpt", "pt", "pth", "onnx",
                "pkl", "joblib", "bin", "safetensors", "gguf", "ggml");
    }

    interface Callback {
        void onResult(Result result);
    }

    static final class Result {
        final List<ModelCatalogItem> items;
        final String updatedAt;
        final boolean changed;
        final boolean notModified;
        final String error;

        Result(List<ModelCatalogItem> items, String updatedAt, boolean changed,
               boolean notModified, String error) {
            this.items = items;
            this.updatedAt = updatedAt;
            this.changed = changed;
            this.notModified = notModified;
            this.error = error == null ? "" : error;
        }
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final ArrayList<Callback> pendingRefreshCallbacks = new ArrayList<>();
    private volatile List<ModelCatalogItem> items = Collections.emptyList();
    private volatile String updatedAt = "";
    private boolean diskLoaded;
    private boolean diskLoading;
    private final ArrayList<Callback> pendingLoadCallbacks = new ArrayList<>();
    private boolean refreshInFlight;

    private ModelCatalogRepository(Context context) {
        appContext = context.getApplicationContext();
    }

    static ModelCatalogRepository get(Context context) {
        if (instance == null) {
            synchronized (ModelCatalogRepository.class) {
                if (instance == null) instance = new ModelCatalogRepository(context);
            }
        }
        return instance;
    }

    List<ModelCatalogItem> currentItems() { return items; }
    String currentUpdatedAt() { return updatedAt; }

    long lastCheckedAt() {
        return prefs().getLong(KEY_LAST_CHECKED, 0L);
    }

    void loadCached(Callback callback) {
        synchronized (lock) {
            if (diskLoaded) {
                post(callback, snapshot(false, false, ""));
                return;
            }
            pendingLoadCallbacks.add(callback);
            if (diskLoading) return;
            diskLoading = true;
        }
        executor.execute(() -> {
            String error = "";
            try {
                File cache = cacheFile();
                if (cache.exists()) applyParsed(parseCatalog(readFile(cache)));
            } catch (Exception problem) {
                error = "本地模型列表读取失败";
            }
            ArrayList<Callback> callbacks;
            synchronized (lock) {
                diskLoaded = true;
                diskLoading = false;
                callbacks = new ArrayList<>(pendingLoadCallbacks);
                pendingLoadCallbacks.clear();
            }
            Result result = snapshot(false, false, error);
            for (Callback queued : callbacks) post(queued, result);
        });
    }

    void refresh(boolean manual, Callback callback) {
        synchronized (lock) {
            pendingRefreshCallbacks.add(callback);
            if (refreshInFlight) return;
            refreshInFlight = true;
        }
        executor.execute(() -> {
            Result result;
            try {
                result = performRefresh(manual);
            } catch (Exception problem) {
                String message = problem.getMessage();
                result = snapshot(false, false,
                        message == null || message.trim().isEmpty() ? "模型列表更新失败" : message);
            }
            ArrayList<Callback> callbacks;
            synchronized (lock) {
                refreshInFlight = false;
                callbacks = new ArrayList<>(pendingRefreshCallbacks);
                pendingRefreshCallbacks.clear();
            }
            for (Callback queued : callbacks) post(queued, result);
        });
    }

    private Result performRefresh(boolean manual) throws Exception {
        File cache = cacheFile();
        boolean hasCache = cache.exists();
        SharedPreferences metadata = prefs();
        HttpURLConnection connection = (HttpURLConnection) new URL(CATALOG_URL).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "CloudFileRelay-Android/" + BuildConfig.VERSION_NAME);
        if (manual) connection.setRequestProperty("Cache-Control", "no-cache");
        if (hasCache) {
            String etag = metadata.getString(KEY_ETAG, "");
            String lastModified = metadata.getString(KEY_LAST_MODIFIED, "");
            if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
            if (!lastModified.isEmpty()) connection.setRequestProperty("If-Modified-Since", lastModified);
        }

        int code = connection.getResponseCode();
        if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
            metadata.edit().putLong(KEY_LAST_CHECKED, System.currentTimeMillis()).apply();
            connection.disconnect();
            return snapshot(false, true, "");
        }
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new Exception("模型列表服务器返回 " + code);
        }

        String raw;
        try (InputStream input = connection.getInputStream()) {
            raw = readText(input);
        }
        String etag = value(connection.getHeaderField("ETag"));
        String lastModified = value(connection.getHeaderField("Last-Modified"));
        connection.disconnect();

        ParsedCatalog parsed = parseCatalog(raw);
        if (parsed.items.isEmpty()) throw new Exception("下载的模型列表为空");
        writeAtomically(cache, raw.getBytes(StandardCharsets.UTF_8));
        metadata.edit()
                .putString(KEY_ETAG, etag)
                .putString(KEY_LAST_MODIFIED, lastModified)
                .putLong(KEY_LAST_CHECKED, System.currentTimeMillis())
                .apply();
        applyParsed(parsed);
        synchronized (lock) { diskLoaded = true; }
        return snapshot(true, false, "");
    }

    static ParsedCatalog parseCatalog(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONArray array = root.optJSONArray("items");
        if (array == null) throw new Exception("模型列表格式不正确");
        ArrayList<ModelCatalogItem> parsed = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) continue;
            String filename = json.optString("filename").trim();
            String shareUrl = json.optString("share_url").trim();
            String extension = ModelCatalogItem.extensionOf(filename);
            if (filename.isEmpty() || shareUrl.isEmpty() || !MODEL_EXTENSIONS.contains(extension)) continue;
            parsed.add(new ModelCatalogItem(filename, json.optString("normalized_name"),
                    shareUrl, json.optString("completed_at")));
        }
        return new ParsedCatalog(Collections.unmodifiableList(parsed), root.optString("updatedAt"));
    }

    private void applyParsed(ParsedCatalog parsed) {
        items = parsed.items;
        updatedAt = parsed.updatedAt;
    }

    private Result snapshot(boolean changed, boolean notModified, String error) {
        return new Result(items, updatedAt, changed, notModified, error);
    }

    private void post(Callback callback, Result result) {
        if (callback != null) main.post(() -> callback.onResult(result));
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private File cacheFile() {
        return new File(appContext.getFilesDir(), CACHE_FILE);
    }

    private static String readFile(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) { return readText(input); }
    }

    private static String readText(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_BYTES) throw new Exception("模型列表文件过大");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void writeAtomically(File destination, byte[] bytes) throws Exception {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception unsupportedAtomicMove) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String value(String value) { return value == null ? "" : value; }

    static final class ParsedCatalog {
        final List<ModelCatalogItem> items;
        final String updatedAt;

        ParsedCatalog(List<ModelCatalogItem> items, String updatedAt) {
            this.items = items;
            this.updatedAt = updatedAt == null ? "" : updatedAt;
        }
    }
}
