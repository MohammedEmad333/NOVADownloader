package com.example.ytdl;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Conservative, opt-in cache maintenance. Only this app's exact task-temp filenames
 * are candidates. Never touch a partial belonging to an unfinished task, recently
 * modified files, thumbnails, other caches, or saved MediaStore downloads.
 */
final class StorageHealth {
    static final long MIN_ORPHAN_AGE_MS = 24L * 60 * 60 * 1000;

    static final class Report {
        final long reclaimableBytes;
        final int reclaimableFiles;
        final long protectedBytes;
        final int protectedFiles;
        final int pausedTasks;
        final int waitingTasks;
        final boolean workerRunning;
        final long availableBytes;

        Report(long reclaimableBytes, int reclaimableFiles, long protectedBytes,
               int protectedFiles, int pausedTasks, int waitingTasks,
               boolean workerRunning, long availableBytes) {
            this.reclaimableBytes = reclaimableBytes;
            this.reclaimableFiles = reclaimableFiles;
            this.protectedBytes = protectedBytes;
            this.protectedFiles = protectedFiles;
            this.pausedTasks = pausedTasks;
            this.waitingTasks = waitingTasks;
            this.workerRunning = workerRunning;
            this.availableBytes = availableBytes;
        }
    }

    static final class Cleanup {
        final long bytesFreed;
        final int filesRemoved;
        final int failures;
        final boolean blockedByTransfer;

        Cleanup(long bytesFreed, int filesRemoved, int failures, boolean blockedByTransfer) {
            this.bytesFreed = bytesFreed;
            this.filesRemoved = filesRemoved;
            this.failures = failures;
            this.blockedByTransfer = blockedByTransfer;
        }
    }

    private StorageHealth() {}

    static Report inspect(Context context) {
        DownloadService.ensureLoaded(context);
        final File external = context.getExternalFilesDir(null);
        final File location = external != null ? external : context.getCacheDir();
        long freeBytes = -1;
        try {
            freeBytes = new StatFs(location.getAbsolutePath()).getAvailableBytes();
        } catch (RuntimeException ignored) {
            // Display unknown rather than pretending the device has no free space.
        }
        return inspect(context.getCacheDir(), DownloadService.snapshot(),
                System.currentTimeMillis(), DownloadService.isWorkerRunning(), freeBytes);
    }

    static Report inspect(File cacheDir, List<DownloadService.Task> tasks,
                          long now, boolean workerRunning, long freeBytes) {
        final Set<Long> keep = unfinishedIds(tasks);
        long reclaimableBytes = 0;
        long protectedBytes = 0;
        int reclaimableFiles = 0;
        int protectedFiles = 0;
        int paused = 0;
        int waiting = 0;
        for (DownloadService.Task task : tasks) {
            if (task.state == DownloadService.State.PAUSED) paused++;
            else if (task.state == DownloadService.State.QUEUED) waiting++;
        }
        final File[] entries = cacheDir.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (!safeRegularFile(entry)) continue;
                final long id = tempTaskId(entry.getName());
                if (id <= 0) continue;
                if (keep.contains(id)) {
                    protectedFiles++;
                    protectedBytes += entry.length();
                } else if (oldEnough(entry, now)) {
                    reclaimableFiles++;
                    reclaimableBytes += entry.length();
                }
            }
        }
        return new Report(reclaimableBytes, reclaimableFiles,
                protectedBytes, protectedFiles, paused, waiting,
                workerRunning, freeBytes);
    }

    static Cleanup cleanup(Context context) {
        DownloadService.ensureLoaded(context);
        return cleanup(context.getCacheDir(), DownloadService.snapshot(),
                System.currentTimeMillis(), DownloadService::isWorkerRunning);
    }

    static Cleanup cleanup(File cacheDir, List<DownloadService.Task> tasks, long now,
                           BooleanSupplier workerRunning) {
        if (workerRunning.getAsBoolean()) return new Cleanup(0, 0, 0, true);
        final Set<Long> keep = unfinishedIds(tasks);
        final File[] entries = cacheDir.listFiles();
        if (entries == null) return new Cleanup(0, 0, 0, false);
        long bytes = 0;
        int removed = 0;
        int failures = 0;
        for (File entry : entries) {
            if (!safeRegularFile(entry)) continue;
            final long id = tempTaskId(entry.getName());
            if (id <= 0 || keep.contains(id) || !oldEnough(entry, now)) continue;
            if (workerRunning.getAsBoolean()) {
                return new Cleanup(bytes, removed, failures, true);
            }
            final long length = entry.length();
            try {
                if (Files.deleteIfExists(entry.toPath())) {
                    removed++;
                    bytes += length;
                }
            } catch (IOException | SecurityException exception) {
                failures++;
            }
        }
        return new Cleanup(bytes, removed, failures, false);
    }

    private static Set<Long> unfinishedIds(List<DownloadService.Task> tasks) {
        final Set<Long> ids = new HashSet<>();
        for (DownloadService.Task task : tasks) {
            if (task != null && !task.state.finished()) ids.add(task.id);
        }
        return ids;
    }

    private static boolean safeRegularFile(File file) {
        return file.isFile() && !Files.isSymbolicLink(file.toPath());
    }

    private static boolean oldEnough(File file, long now) {
        final long modified = file.lastModified();
        return modified > 0 && now >= modified && now - modified >= MIN_ORPHAN_AGE_MS;
    }

    /** Only the six exact names generated by DownloadService for task temp files. */
    static long tempTaskId(String name) {
        if (name == null || name.length() < 5 || name.charAt(1) != '-') return -1;
        final String suffix;
        switch (name.charAt(0)) {
            case 'v':
            case 'a': suffix = ".part"; break;
            case 'm': suffix = ".mp4"; break;
            case 'e': suffix = ".mp3"; break;
            case 's': suffix = ".srt"; break;
            case 'c': suffix = ".jpg"; break;
            default: return -1;
        }
        if (!name.endsWith(suffix)) return -1;
        final String number = name.substring(2, name.length() - suffix.length());
        if (number.isEmpty() || number.charAt(0) == '0') return -1;
        for (int i = 0; i < number.length(); i++) {
            if (number.charAt(i) < '0' || number.charAt(i) > '9') return -1;
        }
        try {
            return Long.parseLong(number);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
