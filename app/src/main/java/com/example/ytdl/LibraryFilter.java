package com.example.ytdl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.function.Predicate;

/** Pure filtering and ordering for the saved/failed library, independent of Android views. */
final class LibraryFilter {
    static final int ALL = 0;
    static final int VIDEO = 1;
    static final int AUDIO = 2;
    static final int SUBTITLES = 3;
    static final int NEEDS_ATTENTION = 4;
    static final int FAVORITES = 5;

    static final int NEWEST = 0;
    static final int OLDEST = 1;
    static final int TITLE = 2;
    static final int GROUP_BY_SOURCE = 3;

    private LibraryFilter() {}

    static List<DownloadService.Task> select(List<DownloadService.Task> all,
                                              String search, int category, int order) {
        return select(all, search, category, order, task -> false);
    }

    static List<DownloadService.Task> select(List<DownloadService.Task> all,
                                              String search, int category, int order,
                                              Predicate<DownloadService.Task> favorites) {
        return select(all, search, category, order, favorites,
                LibraryOrganizer.ALL_SOURCES, null);
    }

    /**
     * Filter by source and optional candidate IDs before sorting, doing one
     * stable sort and leaving the original queue untouched.
     */
    static List<DownloadService.Task> select(List<DownloadService.Task> all,
                                              String search, int category, int order,
                                              Predicate<DownloadService.Task> favorites,
                                              int source, Set<Long> candidates) {
        final String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        final List<DownloadService.Task> result = new ArrayList<>();
        if (all == null) return result;
        final Map<Long, Integer> sources = order == GROUP_BY_SOURCE
                ? new HashMap<>() : null;
        for (DownloadService.Task task : all) {
            if (task == null || task.state == null || !task.state.finished()) continue;
            if (!matchesCategory(task, category, favorites)) continue;
            if (candidates != null && !candidates.contains(task.id)) continue;
            if (source != LibraryOrganizer.ALL_SOURCES || sources != null) {
                final int taskSource = LibraryOrganizer.sourceOf(task);
                if (source != LibraryOrganizer.ALL_SOURCES && taskSource != source) continue;
                if (sources != null) sources.put(task.id, taskSource);
            }
            final String title = task.title == null ? "" : task.title;
            final String artist = task.artist == null ? "" : task.artist;
            final String label = task.spec == null ? "" : task.spec.label;
            if (!query.isEmpty()
                    && !title.toLowerCase(Locale.ROOT).contains(query)
                    && !artist.toLowerCase(Locale.ROOT).contains(query)
                    && !label.toLowerCase(Locale.ROOT).contains(query)) continue;
            result.add(task);
        }
        if (order == OLDEST) {
            result.sort(Comparator.comparingLong(t -> t.id));
        } else if (order == TITLE) {
            result.sort((a, b) -> {
                final String at = a.title == null ? "" : a.title;
                final String bt = b.title == null ? "" : b.title;
                return at.compareToIgnoreCase(bt);
            });
        } else if (order == GROUP_BY_SOURCE) {
            result.sort((a, b) -> {
                final int group = Integer.compare(sources.get(a.id), sources.get(b.id));
                return group != 0 ? group : Long.compare(b.id, a.id);
            });
        } else {
            result.sort((a, b) -> Long.compare(b.id, a.id));
        }
        return result;
    }

    private static boolean matchesCategory(DownloadService.Task task, int category,
                                           Predicate<DownloadService.Task> favorites) {
        if (category == FAVORITES) return task.state == DownloadService.State.DONE
                && favorites != null && favorites.test(task);
        if (category == ALL) return true;
        if (category == NEEDS_ATTENTION) {
            return task.state == DownloadService.State.FAILED
                    || task.state == DownloadService.State.CANCELLED;
        }
        if (task.state != DownloadService.State.DONE) return false;
        final String mime = task.mimeType == null
                ? "" : task.mimeType.toLowerCase(Locale.ROOT);
        if (category == VIDEO) return mime.startsWith("video/");
        if (category == AUDIO) return mime.startsWith("audio/")
                || (task.spec != null && task.spec.kind == DownloadService.Kind.MP3);
        if (category == SUBTITLES) return (task.spec != null && task.spec.kind == DownloadService.Kind.SUBTITLE)
                || mime.startsWith("text/");
        return false;
    }
}
