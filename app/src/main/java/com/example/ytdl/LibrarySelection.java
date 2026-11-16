package com.example.ytdl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory, read-only selection of exact completed saved media references. */
final class LibrarySelection {
    static final int MAX_SELECTED = 100;
    private final Map<Long, String> selected = new LinkedHashMap<>();

    int size() { return selected.size(); }
    void clear() { selected.clear(); }

    boolean contains(DownloadService.Task task) {
        return LibraryFavorites.eligible(task)
                && task.outputUri.equals(selected.get(task.id));
    }

    boolean toggle(DownloadService.Task task, LibraryFileStatus.State availability) {
        if (!LibraryFavorites.eligible(task)
                || LibraryFileStatus.unavailable(availability)) return false;
        if (contains(task)) {
            selected.remove(task.id);
            return true;
        }
        if (selected.size() >= MAX_SELECTED) return false;
        selected.put(task.id, task.outputUri);
        return true;
    }

    List<DownloadService.Task> resolve(List<DownloadService.Task> tasks,
                                      Map<String, LibraryFileStatus.State> statuses) {
        final Map<Long, DownloadService.Task> valid = new HashMap<>();
        if (tasks != null) {
            for (DownloadService.Task task : tasks) {
                if (LibraryFavorites.eligible(task)
                        && (statuses == null || !LibraryFileStatus.unavailable(
                                statuses.get(task.outputUri)))) {
                    valid.put(task.id, task);
                }
            }
        }
        final List<DownloadService.Task> resolved = new ArrayList<>();
        for (Map.Entry<Long, String> entry : selected.entrySet()) {
            final DownloadService.Task task = valid.get(entry.getKey());
            if (task != null && entry.getValue().equals(task.outputUri)) resolved.add(task);
        }
        return resolved;
    }

    void retainValid(List<DownloadService.Task> tasks,
                     Map<String, LibraryFileStatus.State> statuses) {
        final Set<Long> live = new HashSet<>();
        for (DownloadService.Task task : resolve(tasks, statuses)) live.add(task.id);
        selected.keySet().retainAll(live);
    }
}
