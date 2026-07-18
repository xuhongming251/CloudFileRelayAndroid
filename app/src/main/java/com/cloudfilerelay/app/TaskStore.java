package com.cloudfilerelay.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class TaskStore {
    private static final String PREFS = "relay_tasks";
    private static final String KEY = "items";
    private static final String KEY_DELETED = "deleted_ids";

    private TaskStore() {}

    static synchronized List<TaskItem> load(Context context) {
        ArrayList<TaskItem> items = new ArrayList<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                items.add(TaskItem.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    static synchronized void save(Context context, List<TaskItem> items) {
        JSONArray array = new JSONArray();
        for (TaskItem item : items) {
            try { array.put(item.toJson()); } catch (Exception ignored) {}
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, array.toString()).apply();
    }

    static synchronized TaskItem add(Context context, String filename, String provider,
                                     String target, String size, String sourceUrl) {
        TaskItem item = new TaskItem();
        item.id = "local_" + UUID.randomUUID();
        item.filename = filename;
        item.provider = provider;
        item.target = target;
        item.size = size == null || size.isEmpty() ? "--" : size;
        // Signed source URLs are short-lived credentials and are never persisted.
        item.sourceUrl = "";
        item.status = TaskItem.SUBMITTING;
        item.progress = 0;
        item.createdAt = System.currentTimeMillis();
        List<TaskItem> items = load(context);
        items.add(0, item);
        save(context, items);
        return item;
    }

    static synchronized void upsert(Context context, TaskItem updated) {
        if (isDeleted(context, updated.id)) return;
        List<TaskItem> items = load(context);
        boolean found = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(updated.id)) {
                items.set(i, updated);
                found = true;
                break;
            }
        }
        if (!found) items.add(0, updated);
        save(context, items);
    }

    static synchronized void replace(Context context, String previousId, TaskItem updated) {
        if (isDeleted(context, previousId) || isDeleted(context, updated.id)) return;
        List<TaskItem> items = load(context);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(previousId) || items.get(i).id.equals(updated.id)) {
                items.set(i, updated);
                save(context, items);
                return;
            }
        }
        items.add(0, updated);
        save(context, items);
    }

    static synchronized void remove(Context context, String id) {
        List<TaskItem> items = load(context);
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).id.equals(id)) items.remove(i);
        }
        Set<String> deleted = loadDeleted(context);
        deleted.add(id);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, encodeItems(items))
                .putString(KEY_DELETED, encodeStrings(deleted))
                .apply();
    }

    static synchronized void clear(Context context) {
        List<TaskItem> items = load(context);
        Set<String> deleted = loadDeleted(context);
        for (TaskItem item : items) deleted.add(item.id);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, "[]")
                .putString(KEY_DELETED, encodeStrings(deleted))
                .apply();
    }

    private static boolean isDeleted(Context context, String id) {
        return id != null && !id.isEmpty() && loadDeleted(context).contains(id);
    }

    private static Set<String> loadDeleted(Context context) {
        HashSet<String> deleted = new HashSet<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DELETED, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) deleted.add(array.optString(i));
        } catch (Exception ignored) {
        }
        return deleted;
    }

    private static String encodeItems(List<TaskItem> items) {
        JSONArray array = new JSONArray();
        for (TaskItem item : items) {
            try { array.put(item.toJson()); } catch (Exception ignored) {}
        }
        return array.toString();
    }

    private static String encodeStrings(Set<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array.toString();
    }

    static void seedDebugTasks(Context context) {
        if (!BuildConfig.DEBUG) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean("debug_seeded", false) || !load(context).isEmpty()) return;
        String[][] data = {
                {"flux_portrait_v12.safetensors", "Civitai", "quark", "328 MB", TaskItem.UPLOADING, "76"},
                {"SDXL_Turbo_1.0.safetensors", "Hugging Face", "baidu", "6.94 GB", TaskItem.FETCHING, "28"},
                {"qwen_image_fp8.safetensors", "Hugging Face", "mobile", "8.6 GB", TaskItem.FETCHING, "3"},
                {"majicmix_v7.safetensors", "Civitai", "quark", "6.4 GB", TaskItem.COMPLETED, "100"},
                {"detail_enhancer.safetensors", "Civitai", "baidu", "712 MB", TaskItem.COMPLETED, "100"},
                {"model_index.json", "Hugging Face", "quark", "18 KB", TaskItem.COMPLETED, "100"},
                {"anime_lora_v3.safetensors", "Civitai", "mobile", "144 MB", TaskItem.FAILED, "35"}
        };
        ArrayList<TaskItem> items = new ArrayList<>();
        for (String[] row : data) {
            TaskItem item = new TaskItem();
            item.id = "demo_" + UUID.randomUUID();
            item.filename = row[0];
            item.provider = row[1];
            item.target = row[2];
            item.size = row[3];
            item.status = row[4];
            item.progress = Integer.parseInt(row[5]);
            item.createdAt = System.currentTimeMillis();
            if (TaskItem.COMPLETED.equals(item.status)) {
                item.shareUrl = "https://example.com/resource/" + item.id;
            }
            items.add(item);
        }
        save(context, items);
        prefs.edit().putBoolean("debug_seeded", true).apply();
    }
}
