package com.cloudfilerelay.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

final class ModelCatalogSearch {
    private ModelCatalogSearch() {}

    static List<String> tokens(String query) {
        String normalized = ModelCatalogItem.normalizeForSearch(query);
        if (normalized.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (!token.isEmpty()) unique.add(token);
        }
        return new ArrayList<>(unique);
    }

    static List<ModelCatalogItem> search(List<ModelCatalogItem> source, String query) {
        List<String> queryTokens = tokens(query);
        if (queryTokens.isEmpty()) return new ArrayList<>(source);

        ArrayList<ScoredItem> matches = collectMatches(source, queryTokens, false);
        // Keep precise multi-keyword results clean. Typo-tolerant subsequence
        // matching is a fallback only when the direct AND search has no result.
        if (matches.isEmpty()) matches = collectMatches(source, queryTokens, true);
        sortMatches(matches);
        ArrayList<ModelCatalogItem> output = new ArrayList<>(matches.size());
        for (ScoredItem result : matches) output.add(result.item);
        return output;
    }

    private static ArrayList<ScoredItem> collectMatches(List<ModelCatalogItem> source,
                                                         List<String> queryTokens,
                                                         boolean allowFuzzy) {
        String compactPhrase = String.join("", queryTokens);
        ArrayList<ScoredItem> matches = new ArrayList<>();
        for (ModelCatalogItem item : source) {
            int score = 0;
            boolean matched = true;
            for (String token : queryTokens) {
                int tokenScore = scoreToken(item, token, allowFuzzy);
                if (tokenScore < 0) {
                    matched = false;
                    break;
                }
                score += tokenScore;
            }
            if (!matched) continue;
            if (item.compactSearchText.contains(compactPhrase)) score += 45;
            matches.add(new ScoredItem(item, score));
        }
        return matches;
    }

    private static void sortMatches(ArrayList<ScoredItem> matches) {
        matches.sort(Comparator
                .comparingInt((ScoredItem result) -> result.score).reversed()
                .thenComparingLong(result -> -result.item.completedAtMillis)
                .thenComparing(result -> result.item.filename));
    }

    private static int scoreToken(ModelCatalogItem item, String token, boolean allowFuzzy) {
        if (token.isEmpty()) return 0;
        String text = item.searchText;
        String compact = item.compactSearchText;
        if (text.equals(token)) return 180;
        if (text.startsWith(token + " ") || text.startsWith(token)) return 145;
        if (text.contains(" " + token + " ") || text.endsWith(" " + token)) return 135;
        if (text.contains(token)) return 115;

        String compactToken = token.replace(" ", "");
        if (compact.contains(compactToken)) return 105;
        if (!allowFuzzy || compactToken.length() < 3) return -1;
        int fuzzy = subsequenceScore(compact, compactToken);
        return fuzzy < 0 ? -1 : 35 + fuzzy;
    }

    /** Typo-tolerant ordered subsequence score. All query tokens still use AND semantics. */
    private static int subsequenceScore(String text, String token) {
        int textIndex = 0;
        int previous = -1;
        int totalGap = 0;
        int longestGap = 0;
        for (int i = 0; i < token.length(); i++) {
            int found = text.indexOf(token.charAt(i), textIndex);
            if (found < 0) return -1;
            if (previous >= 0) {
                int gap = found - previous - 1;
                totalGap += gap;
                longestGap = Math.max(longestGap, gap);
            }
            previous = found;
            textIndex = found + 1;
        }
        if (longestGap > 12 || totalGap > Math.max(18, token.length() * 4)) return -1;
        return Math.max(1, 28 - totalGap - Math.max(0, text.length() - token.length()) / 14);
    }

    private static final class ScoredItem {
        final ModelCatalogItem item;
        final int score;

        ScoredItem(ModelCatalogItem item, int score) {
            this.item = item;
            this.score = score;
        }
    }
}
