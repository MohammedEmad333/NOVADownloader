package com.example.ytdl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable metadata index for one Library queue snapshot. Source detection and
 * search normalization happen ONCE per refresh, while sort orders are cached.
 * No MediaStore reads, file deletion or mutation of DownloadService.Task.
 */
final class LibraryQueryIndex {
    private final List<Entry> entries;
    private final Map<Integer, List<Entry>> sorted = new HashMap<>();
    private final Map<Long, Integer> sourceById = new HashMap<>();

    private static final class Entry {
        final DownloadService.Task task;
        final int source;
        final boolean eligible;
        final String title;
        final String artist;
        final String label;
        final String mime;

        Entry(DownloadService.Task task) {
            this.task = task;
            source = LibraryOrganizer.sourceOf(task);
            eligible = LibraryFavorites.eligible(task);
            title = normalize(task.title);
            artist = normalize(task.artist);
            label = normalize(task.spec == null ? null : task.spec.label);
            mime = normalize(task.mimeType);
        }

        boolean matches(String term) {
            return term.isEmpty() || title.contains(term) || artist.contains(term)
                    || label.contains(term);
        }

        boolean matchesCategory(int category, Set<Long> favorites) {
            if (category == LibraryFilter.FAVORITES) {
                return task.state == DownloadService.State.DONE
                        && favorites != null && favorites.contains(task.id);
            }
            if (category == LibraryFilter.ALL) return true;
            if (category == LibraryFilter.NEEDS_ATTENTION) {
                return task.state == DownloadService.State.FAILED
                        || task.state == DownloadService.State.CANCELLED;
            }
            if (task.state != DownloadService.State.DONE) return false;
            if (category == LibraryFilter.VIDEO) return mime.startsWith("video/");
            if (category == LibraryFilter.AUDIO) {
                return mime.startsWith("audio/") || (task.spec != null
                        && task.spec.kind == DownloadService.Kind.MP3);
            }
            if (category == LibraryFilter.SUBTITLES) {
                return mime.startsWith("text/") || (task.spec != null
                        && task.spec.kind == DownloadService.Kind.SUBTITLE);
            }
            return false;
        }
    }

    LibraryQueryIndex(List<DownloadService.Task> all) {
        final List<Entry> collected = new ArrayList<>();
        if (all != null) {
            for (DownloadService.Task task : all) {
                if (task == null || task.state == null || !task.state.finished()) continue;
                final Entry entry = new Entry(task);
                collected.add(entry);
                sourceById.put(task.id, entry.source);
            }
        }
        entries = Collections.unmodifiableList(collected);
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    int sourceOf(DownloadService.Task task) {
        if (task == null) return LibraryOrganizer.OTHER;
        final Integer known = sourceById.get(task.id);
        return known == null ? LibraryOrganizer.sourceOf(task) : known;
    }

    int size() { return entries.size(); }

    private List<Entry> ordered(int sort) {
        final List<Entry> cached = sorted.get(sort);
        if (cached != null) return cached;
        final List<Entry> result = new ArrayList<>(entries);
        if (sort == LibraryFilter.OLDEST) {
            result.sort(Comparator.comparingLong(e -> e.task.id));
        } else if (sort == LibraryFilter.TITLE) {
            result.sort((a, b) -> {
                final String at = a.task.title == null ? "" : a.task.title;
                final String bt = b.task.title == null ? "" : b.task.title;
                return at.compareToIgnoreCase(bt);
            });
        } else if (sort == LibraryFilter.GROUP_BY_SOURCE) {
            result.sort((a, b) -> {
                final int group = Integer.compare(a.source, b.source);
                return group != 0 ? group : Long.compare(b.task.id, a.task.id);
            });
        } else {
            result.sort((a, b) -> Long.compare(b.task.id, a.task.id));
        }
        final List<Entry> immutable = Collections.unmodifiableList(result);
        sorted.put(sort, immutable);
        return immutable;
    }

    List<DownloadService.Task> select(String search, int category, int sort, int source,
                                      Set<Long> favorites, Set<Long> duplicates,
                                      LibraryCollections.Collection collection) {
        final String query = normalize(search == null ? "" : search.trim());
        final List<DownloadService.Task> result = new ArrayList<>();
        for (Entry e : ordered(sort)) {
            if (source != LibraryOrganizer.ALL_SOURCES && e.source != source) continue;
            if (!e.matchesCategory(category, favorites) || !e.matches(query)) continue;
            if (duplicates != null && !duplicates.contains(e.task.id)) continue;
            if (collection != null && (!e.eligible
                    || !e.task.outputUri.equals(collection.members.get(e.task.id)))) continue;
            result.add(e.task);
        }
        return result;
    }
}
