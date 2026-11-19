package com.example.ytdl;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LibraryQueryIndexTest {
    private static DownloadService.Task saved(int i) {
        DownloadService.Task task = new DownloadService.Task(
                "https://youtu.be/video" + (i / 3),
                i % 2 == 0 ? "Music " + i : "Video " + i,
                i % 7 == 0 ? DownloadService.Spec.mp3(192)
                        : DownloadService.Spec.muxed(720, "720p"));
        task.state = i % 23 == 0 ? DownloadService.State.FAILED
                : DownloadService.State.DONE;
        task.mimeType = i % 7 == 0 ? "audio/mpeg" : "video/mp4";
        task.outputUri = "content://media/external/downloads/" + (1000 + i);
        task.artist = "Artist " + (i % 13);
        return task;
    }

    @Test public void indexedAndOriginalFiltersReturnSameOrderAndContents() {
        final List<DownloadService.Task> queue = new ArrayList<>();
        for (int i = 1; i <= 400; i++) queue.add(saved(i));
        queue.add(null);
        final LibrarySnapshot snapshot = LibrarySnapshot.capture(queue);
        final LibraryQueryIndex index = new LibraryQueryIndex(snapshot.tasks);
        final Set<Long> favorites = new HashSet<>();
        final Set<Long> duplicates = snapshot.possibleDuplicates;
        for (int i = 0; i < queue.size(); i += 5) {
            if (queue.get(i) != null) favorites.add(queue.get(i).id);
        }
        for (String term : Arrays.asList("", "music", "ARTIST 2", "720P", "no-match")) {
            for (int cat = 0; cat <= LibraryFilter.FAVORITES; cat++) {
                for (int order = 0; order <= LibraryFilter.GROUP_BY_SOURCE; order++) {
                    for (int source : new int[]{0, LibraryOrganizer.YOUTUBE,
                            LibraryOrganizer.X}) {
                        List<DownloadService.Task> expected = LibraryFilter.select(
                                snapshot.tasks, term, cat, order,
                                t -> favorites.contains(t.id), source, null);
                        List<DownloadService.Task> actual = index.select(
                                term, cat, order, source, favorites, null, null);
                        assertEquals("term=" + term + " category=" + cat + " order="
                                + order + " source=" + source, expected, actual);
                    }
                }
            }
        }
        final List<DownloadService.Task> copies = index.select(
                "", LibraryFilter.ALL, LibraryFilter.GROUP_BY_SOURCE,
                LibraryOrganizer.ALL_SOURCES, favorites, duplicates, null);
        assertEquals(LibraryFilter.select(snapshot.tasks, "", LibraryFilter.ALL,
                LibraryFilter.GROUP_BY_SOURCE, t -> favorites.contains(t.id),
                LibraryOrganizer.ALL_SOURCES, duplicates), copies);
    }

    @Test public void tenThousandItemsRemainOrderedAndReadOnlyAcrossQueries() {
        final List<DownloadService.Task> originals = new ArrayList<>();
        for (int i = 1; i <= 10000; i++) originals.add(saved(i));
        final LibraryQueryIndex index = new LibraryQueryIndex(originals);
        assertEquals(10000, index.size());
        final Set<Long> empty = Collections.emptySet();
        for (int attempt = 0; attempt < 12; attempt++) {
            assertEquals(10000,
                    index.select("", LibraryFilter.ALL, LibraryFilter.NEWEST,
                            0, empty, null, null).size());
            final List<DownloadService.Task> hits = index.select(
                    "Artist 4", LibraryFilter.VIDEO,
                    attempt % 2 == 0 ? LibraryFilter.OLDEST : LibraryFilter.GROUP_BY_SOURCE,
                    LibraryOrganizer.YOUTUBE, empty, null, null);
            assertFalse(hits.isEmpty());
            for (DownloadService.Task task : hits) {
                assertTrue(task.artist.contains("4"));
                assertEquals(DownloadService.State.DONE, task.state);
            }
        }
        assertSame(originals.get(0), index.select("", LibraryFilter.ALL,
                LibraryFilter.OLDEST, 0, empty, null, null).get(0));
        assertSame(originals.get(9999), index.select("", LibraryFilter.ALL,
                LibraryFilter.NEWEST, 0, empty, null, null).get(0));
        assertEquals(DownloadService.State.DONE, originals.get(1).state);
        assertEquals("content://media/external/downloads/1002", originals.get(1).outputUri);
    }

    @Test public void collectionIdentityRequiresMatchingSavedUri() {
        final DownloadService.Task one = saved(52);
        final DownloadService.Task two = saved(53);
        final java.util.Map<Long, String> members = new java.util.HashMap<>();
        members.put(one.id, one.outputUri);
        members.put(two.id, "content://media/external/downloads/incorrect");
        final LibraryCollections.Collection collection =
                new LibraryCollections.Collection("example", "Saved", members);
        final LibraryQueryIndex index = new LibraryQueryIndex(Arrays.asList(one, two));
        assertEquals(Collections.singletonList(one),
                index.select("", LibraryFilter.ALL, LibraryFilter.NEWEST, 0,
                        Collections.emptySet(), null, collection));
        assertEquals(DownloadService.State.DONE, two.state);
    }

    @Test public void snapshotsDoNotChangeAfterOriginalListsAreMutated() {
        final List<DownloadService.Task> data = new ArrayList<>();
        data.add(saved(1));
        final LibraryQueryIndex index = new LibraryQueryIndex(data);
        data.clear();
        assertEquals(1, index.select("", 0, 0, 0, null, null, null).size());
    }
}
