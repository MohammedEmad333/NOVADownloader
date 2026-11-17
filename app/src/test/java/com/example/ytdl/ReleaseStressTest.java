package com.example.ytdl;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Functional regression on a large queue, not a timing benchmark: filtering
 * must not reorder or mutate persisted downloads, even when repeated.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ReleaseStressTest {
    @Test public void largeLibraryFilterAndDuplicateReviewStayReadOnly() {
        final List<DownloadService.Task> queue = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            final DownloadService.Task task = new DownloadService.Task(
                    "https://youtu.be/item" + (i / 2), "Video " + i,
                    DownloadService.Spec.muxed(720, "720p"));
            task.state = DownloadService.State.DONE;
            task.outputUri = "content://media/external/downloads/" + (i + 1000);
            task.mimeType = "video/mp4";
            queue.add(task);
        }
        final LibrarySnapshot snapshot = LibrarySnapshot.capture(queue);
        assertEquals(6000, snapshot.saved);
        assertEquals(6000, snapshot.possibleDuplicates.size());
        assertEquals(queue, snapshot.tasks);

        for (int repeat = 0; repeat < 6; repeat++) {
            final List<DownloadService.Task> filtered = LibraryFilter.select(
                    snapshot.tasks, "Video 59", LibraryFilter.ALL,
                    LibraryFilter.GROUP_BY_SOURCE, task -> false,
                    LibraryOrganizer.YOUTUBE, snapshot.possibleDuplicates);
            assertEquals(110, filtered.size());
            assertEquals("Video 5999", filtered.get(0).title);
            assertEquals("Video 59", filtered.get(filtered.size() - 1).title);
        }
        assertEquals("Video 0", queue.get(0).title);
        assertEquals("Video 5999", queue.get(5999).title);
        assertEquals(6000, snapshot.tasks.size());
    }
}
