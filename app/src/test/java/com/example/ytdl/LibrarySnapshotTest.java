package com.example.ytdl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LibrarySnapshotTest {
    private static DownloadService.Task saved(String url, String title, String uri) {
        final DownloadService.Task task = new DownloadService.Task(
                url, title, DownloadService.Spec.muxed(720, "720p"));
        task.state = DownloadService.State.DONE;
        task.outputUri = uri;
        task.mimeType = "video/mp4";
        return task;
    }

    @Test public void snapshotIndexesDuplicatesWithoutMutatingQueue() {
        final DownloadService.Task first = saved("https://youtu.be/dup", "One",
                "content://media/external/downloads/11");
        final DownloadService.Task other = saved("https://youtu.be/other", "Other",
                "content://media/external/downloads/12");
        final DownloadService.Task second = saved("https://youtu.be/dup", "Two",
                "content://media/external/downloads/13");
        final List<DownloadService.Task> queue = Arrays.asList(first, other, second);
        final LibrarySnapshot catalog = LibrarySnapshot.capture(queue);
        assertEquals(3, catalog.saved);
        assertEquals(0, catalog.failed);
        assertEquals(3, catalog.finished);
        assertEquals(2, catalog.possibleDuplicates.size());
        assertEquals(queue, catalog.tasks);
        final List<DownloadService.Task> copies = LibraryFilter.select(
                catalog.tasks, "", LibraryFilter.ALL, LibraryFilter.NEWEST,
                task -> false, LibraryOrganizer.ALL_SOURCES, catalog.possibleDuplicates);
        assertEquals(Arrays.asList(second, first), copies);
        assertEquals(Arrays.asList(first, other, second), queue);
    }

    @Test public void largeLibrariesCanBeFilteredRepeatedlyWithoutChangingSnapshot() {
        final List<DownloadService.Task> queue = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            queue.add(saved("https://youtu.be/" + i, "Video " + i,
                    "content://media/external/downloads/" + (i + 1)));
        }
        final LibrarySnapshot catalog = LibrarySnapshot.capture(queue);
        assertEquals(4000, catalog.saved);
        for (int repeat = 0; repeat < 15; repeat++) {
            final List<DownloadService.Task> matches = LibraryFilter.select(
                    catalog.tasks, "video 399", LibraryFilter.ALL,
                    LibraryFilter.GROUP_BY_SOURCE, task -> false,
                    LibraryOrganizer.YOUTUBE, null);
            assertEquals(11, matches.size());
        }
        assertEquals(queue.get(0), catalog.tasks.get(0));
        assertEquals(queue.get(3999), catalog.tasks.get(3999));
        assertEquals(4000, catalog.tasks.size());
    }
}
