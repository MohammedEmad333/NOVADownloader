package com.example.ytdl;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LibraryCollectionsTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private DownloadService.Task saved(long id, String uri) throws Exception {
        final DownloadService.Task item = new DownloadService.Task(
                "https://youtu.be/sample", "Video",
                DownloadService.Spec.muxed(720, "720p"));
        item.state = DownloadService.State.DONE;
        item.outputUri = uri;
        // The queue assigns ids; tests use reflection to simulate persisted id reuse.
        final java.lang.reflect.Field field = DownloadService.Task.class.getDeclaredField("id");
        field.setAccessible(true);
        field.setLong(item, id);
        return item;
    }

    private void reset() {
        context.getSharedPreferences(LibraryCollections.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void createsRenamesAndRemovesOnlyLocalLabels() throws Exception {
        reset();
        try {
            final LibraryCollections.Collection group =
                    LibraryCollections.create(context, "  My Videos  ");
            assertEquals("My Videos", group.name);
            final DownloadService.Task item = saved(101,
                    "content://media/external/downloads/101");
            assertEquals(1, LibraryCollections.add(context, group.id,
                    Collections.singletonList(item)));
            assertEquals(1, LibraryCollections.byId(LibraryCollections.list(context),
                    group.id).count());
            LibraryCollections.rename(context, group.id, "Travel");
            assertTrue(LibraryCollections.byId(LibraryCollections.list(context),
                    group.id).contains(item));
            assertEquals("Travel", LibraryCollections.list(context).get(0).name);
            assertTrue(LibraryCollections.remove(context, group.id));
            assertTrue(LibraryCollections.list(context).isEmpty());
            assertEquals(DownloadService.State.DONE, item.state);
            assertEquals("content://media/external/downloads/101", item.outputUri);
        } finally { reset(); }
    }

    @Test public void preventsDuplicateNamesAndInvalidEntries() throws Exception {
        reset();
        try {
            LibraryCollections.create(context, "مشاهدة");
            try {
                LibraryCollections.create(context, " مشاهدة ");
                fail("same name should be rejected");
            } catch (IllegalArgumentException expected) { }
            try {
                LibraryCollections.create(context, " ");
                fail("empty name should be rejected");
            } catch (IllegalArgumentException expected) { }
            try {
                LibraryCollections.create(context, "x".repeat(60));
                fail("long name should be rejected");
            } catch (IllegalArgumentException expected) { }

            final String id = LibraryCollections.list(context).get(0).id;
            final DownloadService.Task unfinished = saved(201,
                    "content://media/external/downloads/201");
            unfinished.state = DownloadService.State.PAUSED;
            final DownloadService.Task remote = saved(202, "https://media.example/x");
            assertEquals(0, LibraryCollections.add(context, id,
                    Arrays.asList(unfinished, remote)));
            assertEquals(0, LibraryCollections.list(context).get(0).count());
        } finally { reset(); }
    }

    @Test public void membershipAlwaysChecksBothTaskIdAndSavedUri() throws Exception {
        reset();
        try {
            final String id = LibraryCollections.create(context, "Keep").id;
            final DownloadService.Task first = saved(250,
                    "content://media/external/video/media/250");
            assertEquals(1, LibraryCollections.add(context, id,
                    Collections.singletonList(first)));
            final DownloadService.Task reused = saved(250,
                    "content://media/external/video/media/251");
            assertFalse(LibraryCollections.byId(LibraryCollections.list(context), id)
                    .contains(reused));
            assertEquals(0, LibraryCollections.removeMembers(context, id,
                    Collections.singletonList(reused)));
            assertTrue(LibraryCollections.byId(LibraryCollections.list(context), id)
                    .contains(first));
            assertEquals(1, LibraryCollections.removeMembers(context, id,
                    Collections.singletonList(first)));
            assertEquals(0, LibraryCollections.list(context).get(0).count());
            assertEquals(DownloadService.State.DONE, first.state);
        } finally { reset(); }
    }

    @Test public void bulkAddsPersistAndDuplicateAddsDoNotMultiplyCounts() throws Exception {
        reset();
        try {
            final String id = LibraryCollections.create(context, "Sessions").id;
            final DownloadService.Task first = saved(301,
                    "content://media/external/downloads/301");
            final DownloadService.Task second = saved(302,
                    "content://media/external/downloads/302");
            assertEquals(2, LibraryCollections.add(context, id,
                    Arrays.asList(first, second, first)));
            final List<LibraryCollections.Collection> restored = LibraryCollections.list(context);
            assertEquals(2, restored.get(0).count());
            assertTrue(restored.get(0).contains(second));
            assertEquals(0, LibraryCollections.add(context, id,
                    Arrays.asList(first, second)));
            assertEquals(2, LibraryCollections.list(context).get(0).count());
        } finally { reset(); }
    }

    @Test public void corruptMetadataIsNeverSilentlyReplaced() {
        reset();
        final String raw = "{ invalid json";
        try {
            context.getSharedPreferences(LibraryCollections.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(LibraryCollections.KEY, raw).commit();
            try {
                LibraryCollections.create(context, "Would erase backup");
                fail("corrupted metadata must fail closed");
            } catch (IllegalStateException expected) { }
            assertEquals(raw, context.getSharedPreferences(
                    LibraryCollections.PREFS, Context.MODE_PRIVATE)
                    .getString(LibraryCollections.KEY, null));
        } finally { reset(); }
    }
}
