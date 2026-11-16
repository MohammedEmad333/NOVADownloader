package com.example.ytdl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cache the read-only queue snapshot, counts and possible copies once per Library
 * load. Search and spinner changes must not rescan the download queue.
 */
final class LibrarySnapshot {
    static final LibrarySnapshot EMPTY = capture(Collections.emptyList());

    final List<DownloadService.Task> tasks;
    final Set<Long> possibleDuplicates;
    final int saved;
    final int failed;
    final int finished;

    private LibrarySnapshot(List<DownloadService.Task> tasks, Set<Long> duplicates,
                            int saved, int failed, int finished) {
        this.tasks = Collections.unmodifiableList(tasks);
        this.possibleDuplicates = Collections.unmodifiableSet(duplicates);
        this.saved = saved;
        this.failed = failed;
        this.finished = finished;
    }

    static LibrarySnapshot capture(List<DownloadService.Task> input) {
        final List<DownloadService.Task> entries = new ArrayList<>();
        int saved = 0;
        int failed = 0;
        int finished = 0;
        if (input != null) {
            for (DownloadService.Task task : input) {
                if (task == null || task.state == null) continue;
                entries.add(task);
                if (task.state == DownloadService.State.DONE) saved++;
                if (task.state == DownloadService.State.FAILED) failed++;
                if (task.state.finished()) finished++;
            }
        }
        return new LibrarySnapshot(entries,
                new HashSet<>(LibraryOrganizer.possibleDuplicateIds(entries)),
                saved, failed, finished);
    }
}
