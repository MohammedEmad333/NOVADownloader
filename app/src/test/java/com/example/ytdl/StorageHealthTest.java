package com.example.ytdl;

import android.content.Context;
import android.view.View;
import android.widget.Button;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class StorageHealthTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final long NOW = 1_900_000_000_000L;

    private static DownloadService.Task task(long id, DownloadService.State state) throws Exception {
        final DownloadService.Task item = new DownloadService.Task(
                "https://example.org/watch?v=" + id, "Task",
                DownloadService.Spec.muxed(720, "720p"));
        final org.json.JSONObject data = item.toJson();
        data.put("id", id);
        final DownloadService.Task restored = DownloadService.Task.fromJson(data);
        restored.state = state;
        return restored;
    }

    private File candidate(String filename, boolean stale) throws Exception {
        final File file = folder.newFile(filename);
        Files.write(file.toPath(), "DATA".getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(NOW - (stale
                ? StorageHealth.MIN_ORPHAN_AGE_MS + 1000 : 5000)));
        return file;
    }

    @Test public void onlyRecognizedTaskTemporaryFilesMatch() {
        for (String name : new String[]{"v-1.part", "a-20.part", "m-3.mp4",
                "e-9.mp3", "s-9.srt", "c-9.jpg"}) {
            assertTrue(name, StorageHealth.tempTaskId(name) > 0);
        }
        for (String name : new String[]{"thumb-1.jpg", "queue.json", "v-1.mp4",
                "e-5.part", "v-00.part", "v--1.part", "v-0.part",
                "v-123.part.old", "other-v-1.part", "v-99999999999999999999999.part"}) {
            assertEquals(name, -1, StorageHealth.tempTaskId(name));
        }
    }

    @Test public void pausedAndQueuedPartialFilesAreAlwaysProtected() throws Exception {
        File paused = candidate("v-11.part", true);
        File waiting = candidate("a-12.part", true);
        File orphan = candidate("m-13.mp4", true);
        final java.util.List<DownloadService.Task> tasks = Arrays.asList(
                task(11, DownloadService.State.PAUSED),
                task(12, DownloadService.State.QUEUED));
        StorageHealth.Report report = StorageHealth.inspect(folder.getRoot(), tasks,
                NOW, false, 123456);
        assertEquals(2, report.protectedFiles);
        assertEquals(8, report.protectedBytes);
        assertEquals(1, report.reclaimableFiles);
        StorageHealth.Cleanup result = StorageHealth.cleanup(
                folder.getRoot(), tasks, NOW, () -> false);
        assertEquals(1, result.filesRemoved);
        assertFalse(orphan.exists());
        assertTrue(paused.exists());
        assertTrue(waiting.exists());
    }

    @Test public void activeWorkerPreventsAnyDeletion() throws Exception {
        File orphan = candidate("v-20.part", true);
        StorageHealth.Cleanup result = StorageHealth.cleanup(folder.getRoot(),
                Collections.emptyList(), NOW, () -> true);
        assertTrue(result.blockedByTransfer);
        assertEquals(0, result.filesRemoved);
        assertTrue(orphan.exists());
    }

    @Test public void cleanupStopsWhenWorkerStartsBetweenCandidates() throws Exception {
        File first = candidate("v-31.part", true);
        File second = candidate("a-31.part", true);
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        StorageHealth.Cleanup result = StorageHealth.cleanup(folder.getRoot(),
                Collections.emptyList(), NOW, () -> checks.incrementAndGet() >= 3);
        assertTrue(result.blockedByTransfer);
        assertEquals(1, result.filesRemoved);
        assertEquals(1, (first.exists() ? 1 : 0) + (second.exists() ? 1 : 0));
    }

    @Test public void newerAndUnrelatedFilesAreNeverRemoved() throws Exception {
        File fresh = candidate("v-99.part", false);
        File unknown = candidate("my-important-notes.txt", true);
        File saved = candidate("m-12.mp3", true);
        StorageHealth.Cleanup result = StorageHealth.cleanup(folder.getRoot(),
                Collections.emptyList(), NOW, () -> false);
        assertEquals(0, result.filesRemoved);
        assertTrue(fresh.exists());
        assertTrue(unknown.exists());
        assertTrue(saved.exists());
    }

    @Test public void completedTaskTempFilesCanBeRemovedWithoutTouchingOutput() throws Exception {
        File oldTemp = candidate("e-52.mp3", true);
        File keptMedia = candidate("Saved.mp3", true);
        StorageHealth.Cleanup result = StorageHealth.cleanup(folder.getRoot(),
                Collections.singletonList(task(52, DownloadService.State.DONE)),
                NOW, () -> false);
        assertEquals(1, result.filesRemoved);
        assertEquals(4, result.bytesFreed);
        assertFalse(oldTemp.exists());
        assertTrue(keptMedia.exists());
    }

    @Test public void storageScreenOpensWithARealView() {
        final StorageActivity activity = Robolectric.buildActivity(StorageActivity.class)
                .create().start().resume().get();
        final View root = activity.findViewById(android.R.id.content);
        assertNotNull(root);
    }
}
