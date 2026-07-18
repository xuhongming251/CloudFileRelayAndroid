package com.cloudfilerelay.app;

import org.json.JSONException;
import org.json.JSONObject;

final class TaskItem {
    static final String SUBMITTING = "submitting";
    static final String QUEUED = "queued";
    static final String FETCHING = "fetching";
    static final String UPLOADING = "uploading";
    static final String COMPLETED = "completed";
    static final String FAILED = "failed";
    static final String SERVICE_REQUIRED = "service_required";

    String id;
    String filename;
    String provider;
    String target;
    String size;
    String status;
    String sourceUrl;
    String shareUrl;
    String workflowFile;
    long runId;
    int progress;
    long createdAt;

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("filename", filename);
        json.put("provider", provider);
        json.put("target", target);
        json.put("size", size);
        json.put("status", status);
        json.put("sourceUrl", sourceUrl);
        json.put("shareUrl", shareUrl);
        json.put("workflowFile", workflowFile);
        json.put("runId", runId);
        json.put("progress", progress);
        json.put("createdAt", createdAt);
        return json;
    }

    static TaskItem fromJson(JSONObject json) {
        TaskItem item = new TaskItem();
        item.id = json.optString("id");
        item.filename = json.optString("filename", "file");
        item.provider = json.optString("provider", "Web");
        item.target = json.optString("target", "quark");
        item.size = json.optString("size", "--");
        item.status = json.optString("status", FETCHING);
        // Migrate tasks persisted by older builds. "queued" is only an internal
        // executor detail and should not interrupt the visible transfer state.
        if (QUEUED.equals(item.status)) item.status = FETCHING;
        item.sourceUrl = json.optString("sourceUrl");
        item.shareUrl = json.optString("shareUrl");
        item.workflowFile = json.optString("workflowFile", "upload.yml");
        item.runId = json.optLong("runId", 0L);
        item.progress = json.optInt("progress", 0);
        item.createdAt = json.optLong("createdAt", System.currentTimeMillis());
        return item;
    }

    String targetName() {
        switch (target) {
            case "baidu": return "百度网盘";
            case "mobile": return "移动云盘";
            default: return "夸克网盘";
        }
    }

    String statusName() {
        switch (status) {
            case SUBMITTING: return "创建中";
            case QUEUED:
            case FETCHING: return "获取中";
            case UPLOADING: return "上传中";
            case COMPLETED: return "已完成";
            case FAILED: return "失败";
            case SERVICE_REQUIRED: return "待提交";
            default: return "处理中";
        }
    }
}
