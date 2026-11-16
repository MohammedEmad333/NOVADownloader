package com.example.ytdl;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LibraryFileStatusTest {
    private static DownloadService.Task saved(String uri) {
        final DownloadService.Task task = new DownloadService.Task(
                "https://youtu.be/video", "Original",
                DownloadService.Spec.muxed(720, "720p"));
        task.outputUri = uri;
        task.state = DownloadService.State.DONE;
        return task;
    }

    @Test public void missingAndDeniedFilesAreOnlyMarkedNotRemoved() {
        final DownloadService.Task present = saved("content://media/external/downloads/101");
        final DownloadService.Task missing = saved("content://media/external/downloads/102");
        final DownloadService.Task denied = saved("content://media/external/downloads/103");
        final List<DownloadService.Task> originals = Arrays.asList(present, missing, denied);
        final Map<String, LibraryFileStatus.State> found = LibraryFileStatus.scan(
                originals, uri -> {
                    if (uri.equals(Uri.parse(missing.outputUri))) return LibraryFileStatus.State.MISSING;
                    if (uri.equals(Uri.parse(denied.outputUri))) throw new SecurityException();
                    return LibraryFileStatus.State.PRESENT;
                });
        assertEquals(LibraryFileStatus.State.PRESENT, found.get(present.outputUri));
        assertEquals(LibraryFileStatus.State.MISSING, found.get(missing.outputUri));
        assertEquals(LibraryFileStatus.State.NO_ACCESS, found.get(denied.outputUri));
        assertFalse(LibraryFileStatus.unavailable(found.get(present.outputUri)));
        assertTrue(LibraryFileStatus.unavailable(found.get(missing.outputUri)));
        assertTrue(LibraryFileStatus.unavailable(found.get(denied.outputUri)));
        assertEquals(DownloadService.State.DONE, missing.state);
        assertEquals(missing, originals.get(1));
        assertEquals("content://media/external/downloads/102", missing.outputUri);
    }

    @Test public void duplicateUrisCheckedOnceAndUnsafeOnesNeverOpened() {
        final DownloadService.Task original = saved("content://media/external/video/media/201");
        final DownloadService.Task second = saved(original.outputUri);
        final DownloadService.Task remote = saved("https://example.org/video.mp4");
        final DownloadService.Task notFinished = saved("content://media/external/video/media/202");
        notFinished.state = DownloadService.State.QUEUED;
        final AtomicInteger checks = new AtomicInteger();
        final Map<String, LibraryFileStatus.State> found = LibraryFileStatus.scan(
                Arrays.asList(null, original, second, remote, notFinished),
                uri -> { checks.incrementAndGet(); return LibraryFileStatus.State.PRESENT; });
        assertEquals(1, checks.get());
        assertEquals(1, found.size());
        assertFalse(found.containsKey(remote.outputUri));
        assertFalse(found.containsKey(notFinished.outputUri));
    }

    @Test public void unknownResultCannotBeMistakenForMissingFile() {
        final DownloadService.Task one = saved("content://media/external/audio/media/306");
        final Map<String, LibraryFileStatus.State> results = LibraryFileStatus.scan(
                Arrays.asList(one), uri -> { throw new IllegalStateException("provider busy"); });
        assertEquals(LibraryFileStatus.State.UNKNOWN, results.get(one.outputUri));
        assertFalse(LibraryFileStatus.unavailable(results.get(one.outputUri)));
    }

    @Test public void thousandsOfEntriesNeedOneProbePerDistinctSafeUri() {
        final List<DownloadService.Task> many = new ArrayList<>();
        for (int i = 1; i <= 3000; i++) {
            many.add(saved("content://media/external/downloads/" + i));
        }
        many.add(saved("content://media/external/downloads/3000"));
        final AtomicInteger probes = new AtomicInteger();
        final Map<String, LibraryFileStatus.State> results = LibraryFileStatus.scan(
                many, uri -> { probes.incrementAndGet(); return LibraryFileStatus.State.PRESENT; });
        assertEquals(3000, probes.get());
        assertEquals(3000, results.size());
        assertEquals(3001, many.size());
    }
}
