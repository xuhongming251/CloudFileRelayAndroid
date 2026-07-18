package com.cloudfilerelay.app;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;

final class ModelCatalogItem {
    final String filename;
    final String normalizedName;
    final String shareUrl;
    final String completedAt;
    final long completedAtMillis;
    final String extension;
    final String searchText;
    final String compactSearchText;

    ModelCatalogItem(String filename, String normalizedName, String shareUrl, String completedAt) {
        this.filename = filename == null ? "" : filename.trim();
        this.normalizedName = normalizedName == null || normalizedName.trim().isEmpty()
                ? this.filename.toLowerCase(Locale.ROOT) : normalizedName.trim();
        this.shareUrl = shareUrl == null ? "" : shareUrl.trim();
        this.completedAt = completedAt == null ? "" : completedAt.trim();
        this.completedAtMillis = parseTime(this.completedAt);
        this.extension = extensionOf(this.filename);
        this.searchText = normalizeForSearch(this.filename + " " + this.normalizedName);
        this.compactSearchText = this.searchText.replace(" ", "");
    }

    String typeLabel() {
        switch (extension) {
            case "safetensors": return "ST";
            case "joblib": return "JOB";
            default: return extension.isEmpty() ? "FILE" : extension.toUpperCase(Locale.ROOT);
        }
    }

    static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String normalizeForSearch(String value) {
        if (value == null || value.isEmpty()) return "";
        String source = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT);
        StringBuilder output = new StringBuilder(source.length());
        boolean previousSpace = true;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (Character.getType(ch) == Character.NON_SPACING_MARK) continue;
            if (Character.isLetterOrDigit(ch)) {
                output.append(ch);
                previousSpace = false;
            } else if (!previousSpace) {
                output.append(' ');
                previousSpace = true;
            }
        }
        int length = output.length();
        if (length > 0 && output.charAt(length - 1) == ' ') output.setLength(length - 1);
        return output.toString();
    }

    private static long parseTime(String value) {
        if (value == null || value.isEmpty()) return 0L;
        try { return Instant.parse(value).toEpochMilli(); }
        catch (Exception ignored) { return 0L; }
    }
}
