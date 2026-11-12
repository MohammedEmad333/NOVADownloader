package com.example.ytdl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Stable, testable playlist selection helpers; never reorders the user's chosen items. */
final class PlaylistTools {
    private PlaylistTools() {}

    static <T> List<T> uniqueByUrl(List<T> entries, Function<T, String> getUrl) {
        final List<T> selected = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        if (entries == null) return selected;
        for (T entry : entries) {
            if (entry == null) continue;
            final String url = getUrl.apply(entry);
            if (url == null || url.trim().isEmpty()) continue;
            if (seen.add(url.trim())) selected.add(entry);
        }
        return selected;
    }

    static List<Integer> matches(List<String> titles, String search) {
        final List<Integer> indices = new ArrayList<>();
        final String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < titles.size(); i++) {
            final String title = titles.get(i);
            if (query.isEmpty() || (title != null
                    && title.toLowerCase(Locale.ROOT).contains(query))) indices.add(i);
        }
        return indices;
    }

    static boolean allSelected(boolean[] selected, List<Integer> shown) {
        if (shown.isEmpty()) return false;
        for (int i : shown) if (!selected[i]) return false;
        return true;
    }

    static void selectVisible(boolean[] selected, List<Integer> shown, boolean value) {
        for (int i : shown) selected[i] = value;
    }
}
