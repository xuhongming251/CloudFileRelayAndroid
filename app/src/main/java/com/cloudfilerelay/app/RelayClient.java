package com.cloudfilerelay.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Direct GitHub Actions client. No relay gateway is involved. */
final class RelayClient {
    interface Callback {
        void onSuccess(TaskItem item);
        void onError(TaskItem item, String message);
    }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 25_000;

    private RelayClient() {}

    static boolean isConfigured() {
        return !BuildConfig.GITHUB_TOKEN.trim().isEmpty()
                && !BuildConfig.GITHUB_OWNER.trim().isEmpty()
                && !BuildConfig.GITHUB_REPO.trim().isEmpty();
    }

    static void submit(Context context, TaskItem item, String downloadUrl, Callback callback) {
        if (!isConfigured()) {
            item.status = TaskItem.SERVICE_REQUIRED;
            TaskStore.upsert(context, item);
            MAIN.post(() -> callback.onError(item, "GitHub Actions Token 尚未配置"));
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                String previousId = item.id;
                String traceId = createTraceId(item.target);
                String workflowFile = workflowFor(downloadUrl);

                JSONObject inputs = new JSONObject();
                inputs.put("trace_id", traceId);
                inputs.put("url", downloadUrl);
                inputs.put("local_file", item.filename);
                inputs.put("channel", channelValue(item.target));

                JSONObject request = new JSONObject();
                request.put("ref", BuildConfig.GITHUB_REF);
                request.put("inputs", inputs);

                requestJson("POST", workflowPath(workflowFile) + "/dispatches", request);

                item.id = traceId;
                item.workflowFile = workflowFile;
                item.runId = 0L;
                // The dispatch has already been accepted. Show immediate activity
                // while GitHub exposes the new run id instead of a long-lived queue.
                item.status = TaskItem.FETCHING;
                item.progress = 3;
                TaskStore.replace(context, previousId, item);
                MAIN.post(() -> callback.onSuccess(item));
            } catch (Exception error) {
                item.status = TaskItem.FAILED;
                TaskStore.upsert(context, item);
                MAIN.post(() -> callback.onError(item, message(error, "创建任务失败")));
            }
        });
    }

    static void refresh(Context context, TaskItem item, Callback callback) {
        if (!isConfigured() || item.id.startsWith("local_") || item.id.startsWith("demo_")) return;

        EXECUTOR.execute(() -> {
            try {
                String workflowFile = empty(item.workflowFile) ? "upload.yml" : item.workflowFile;
                if (item.runId <= 0) {
                    item.runId = findRunId(workflowFile, item.id);
                    if (item.runId <= 0) {
                        item.status = TaskItem.FETCHING;
                        item.progress = Math.max(item.progress, 3);
                        TaskStore.upsert(context, item);
                        MAIN.post(() -> callback.onSuccess(item));
                        return;
                    }
                }

                JSONObject run = requestJson("GET", repoPath() + "/actions/runs/" + item.runId, null);
                String runStatus = run.optString("status");
                if (!"completed".equals(runStatus)) {
                    int progress = readProgress(item.runId);
                    if (progress > item.progress) item.progress = progress;
                    if ("queued".equals(runStatus) || "requested".equals(runStatus)
                            || "waiting".equals(runStatus) || "pending".equals(runStatus)) {
                        // A workflow can briefly report an internal GitHub queue state
                        // after the dispatch has already been accepted. For the user this
                        // is still one continuous transfer, so never move the task back to
                        // a queue state or reset its visible progress.
                        item.status = TaskItem.FETCHING;
                        item.progress = Math.max(item.progress, 3);
                    } else {
                        item.status = item.progress >= 50 ? TaskItem.UPLOADING : TaskItem.FETCHING;
                    }
                } else {
                    applyCompletedResult(item, run);
                }

                TaskStore.upsert(context, item);
                MAIN.post(() -> callback.onSuccess(item));
            } catch (Exception error) {
                MAIN.post(() -> callback.onError(item, message(error, "刷新失败")));
            }
        });
    }

    private static void applyCompletedResult(TaskItem item, JSONObject run) throws Exception {
        JSONObject result = readResultArtifact(item.runId);
        String conclusion = run.optString("conclusion");
        String resultStatus = result == null ? "" : result.optString("status");
        String shareUrl = result == null ? "" : result.optString("share_url", result.optString("url"));

        if ("success".equals(conclusion) && !"error".equals(resultStatus) && !empty(shareUrl)) {
            item.status = TaskItem.COMPLETED;
            item.progress = 100;
            item.shareUrl = shareUrl;
        } else {
            item.status = TaskItem.FAILED;
            if (result != null) {
                String detail = result.optString("error", result.optString("message"));
                if (!empty(detail)) item.shareUrl = "";
            }
        }
    }

    private static long findRunId(String workflowFile, String traceId) throws Exception {
        String query = "?event=workflow_dispatch&branch=" + encode(BuildConfig.GITHUB_REF) + "&per_page=100";
        JSONObject response = requestJson("GET", workflowPath(workflowFile) + "/runs" + query, null);
        JSONArray runs = response.optJSONArray("workflow_runs");
        if (runs == null) return 0L;
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run == null) continue;
            String name = run.optString("name") + " " + run.optString("display_title");
            if (name.contains(traceId)) return run.optLong("id", 0L);
        }
        return 0L;
    }

    private static int readProgress(long runId) {
        try {
            JSONObject response = requestJson("GET", repoPath() + "/actions/runs/" + runId + "/jobs", null);
            JSONArray jobs = response.optJSONArray("jobs");
            if (jobs == null || jobs.length() == 0) return 0;
            JSONArray steps = jobs.getJSONObject(0).optJSONArray("steps");
            if (steps == null) return 0;
            int latest = 0;
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                String status = step.optString("status");
                if (!"completed".equals(status) && !"in_progress".equals(status)) continue;
                latest = Math.max(latest, leadingPercent(step.optString("name")));
            }
            return latest;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int leadingPercent(String name) {
        int index = 0;
        while (index < name.length() && Character.isDigit(name.charAt(index))) index++;
        if (index == 0) return 0;
        try { return Math.min(99, Integer.parseInt(name.substring(0, index))); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static JSONObject readResultArtifact(long runId) throws Exception {
        JSONObject response = requestJson("GET", repoPath() + "/actions/runs/" + runId + "/artifacts", null);
        JSONArray artifacts = response.optJSONArray("artifacts");
        if (artifacts == null) return null;
        long artifactId = 0L;
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject artifact = artifacts.optJSONObject(i);
            if (artifact != null && "result".equals(artifact.optString("name"))) {
                artifactId = artifact.optLong("id", 0L);
                break;
            }
        }
        if (artifactId <= 0) return null;

        byte[] zipBytes = requestBytes(repoPath() + "/actions/artifacts/" + artifactId + "/zip");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("result.json".equals(entry.getName()) || entry.getName().endsWith("/result.json")) {
                    return new JSONObject(new String(readAllBytes(zip), StandardCharsets.UTF_8));
                }
            }
        }
        return null;
    }

    private static JSONObject requestJson(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = open(new URL(apiBase() + path), method, true);
        if (body != null) {
            connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int code = connection.getResponseCode();
        String raw = readText(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
        connection.disconnect();
        if (code < 200 || code >= 300) throw githubError(code, raw);
        return empty(raw) ? new JSONObject() : new JSONObject(raw);
    }

    private static byte[] requestBytes(String path) throws Exception {
        URL current = new URL(apiBase() + path);
        for (int redirects = 0; redirects <= 5; redirects++) {
            boolean authorize = sameHost(current, new URL(apiBase()));
            HttpURLConnection connection = open(current, "GET", authorize);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (empty(location)) throw new Exception("GitHub artifact 跳转地址无效");
                current = new URL(current, location);
                continue;
            }
            if (code < 200 || code >= 300) {
                String raw = readText(connection.getErrorStream());
                connection.disconnect();
                throw githubError(code, raw);
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = readAllBytes(input);
                connection.disconnect();
                return bytes;
            }
        }
        throw new Exception("GitHub artifact 跳转次数过多");
    }

    private static HttpURLConnection open(URL url, String method, boolean authorize) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "CloudFileRelay-Android/" + BuildConfig.VERSION_NAME);
        if (authorize) connection.setRequestProperty("Authorization", "Bearer " + BuildConfig.GITHUB_TOKEN);
        return connection;
    }

    private static Exception githubError(int code, String raw) {
        String detail = "";
        try { detail = new JSONObject(raw).optString("message"); } catch (Exception ignored) {}
        if (code == 401 || code == 403) return new Exception("GitHub Token 无效或权限不足 (" + code + ")");
        if (code == 404) return new Exception("未找到 GitHub 仓库或工作流 (404)");
        return new Exception("GitHub Actions 返回 " + code + (empty(detail) ? "" : "：" + detail));
    }

    private static String workflowPath(String workflowFile) throws Exception {
        return repoPath() + "/actions/workflows/" + encode(workflowFile);
    }

    private static String repoPath() {
        return "/repos/" + BuildConfig.GITHUB_OWNER + "/" + BuildConfig.GITHUB_REPO;
    }

    private static String apiBase() {
        return BuildConfig.GITHUB_API_BASE.replaceAll("/+$", "");
    }

    private static String workflowFor(String url) {
        return url != null && url.startsWith("https://pan.baidu.com") ? "upload_linux.yml" : "upload.yml";
    }

    private static String channelValue(String target) {
        if ("baidu".equals(target)) return "1";
        if ("mobile".equals(target)) return "2";
        return "0";
    }

    private static String createTraceId(String target) {
        String tag = "baidu".equals(target) ? "baidu" : "mobile".equals(target) ? "mobile" : "quark";
        String time = new SimpleDateFormat("MMdd_HHmmss", Locale.ROOT).format(new Date());
        return "task_" + time + "_" + tag + "_" + UUID.randomUUID().toString().substring(0, 4);
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static boolean sameHost(URL first, URL second) {
        return first.getProtocol().equalsIgnoreCase(second.getProtocol())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URL url) {
        return url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
    }

    private static String readText(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static byte[] readAllBytes(InputStream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String message(Exception error, String fallback) {
        return empty(error.getMessage()) ? fallback : error.getMessage();
    }
}
