package com.example.ytdl;

import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LibrarySelectionTest {
    private DownloadService.Task saved(String uri, String mime) {
        final DownloadService.Task item = new DownloadService.Task(
                "https://youtu.be/sample", "Clip",
                DownloadService.Spec.muxed(720, "720p"));
        item.state = DownloadService.State.DONE;
        item.outputUri = uri;
        item.mimeType = mime;
        return item;
    }

    @Test public void selectedItemsMustBeCompletedOwnedMedia() {
        final LibrarySelection selections = new LibrarySelection();
        final DownloadService.Task saved = saved("content://media/external/video/media/11",
                "video/mp4");
        assertTrue(selections.toggle(saved, null));
        assertEquals(1, selections.size());
        assertTrue(selections.contains(saved));
        assertTrue(selections.toggle(saved, null));
        assertEquals(0, selections.size());
        saved.state = DownloadService.State.PAUSED;
        assertFalse(selections.toggle(saved, null));
        saved.state = DownloadService.State.DONE;
        saved.outputUri = "https://example.com/video.mp4";
        assertFalse(selections.toggle(saved, null));
        assertEquals(0, selections.size());
    }

    @Test public void unavailableOrChangedFilesAreRemovedFromSelection() {
        final LibrarySelection selections = new LibrarySelection();
        final DownloadService.Task one = saved(
                "content://media/external/downloads/21", "video/mp4");
        final DownloadService.Task two = saved(
                "content://media/external/downloads/22", "video/mp4");
        selections.toggle(one, null);
        selections.toggle(two, null);
        final Map<String, LibraryFileStatus.State> statuses = new HashMap<>();
        statuses.put(one.outputUri, LibraryFileStatus.State.MISSING);
        selections.retainValid(Arrays.asList(one, two), statuses);
        assertEquals(1, selections.size());
        assertEquals(Collections.singletonList(two),
                selections.resolve(Arrays.asList(one, two), statuses));
        two.outputUri = "content://media/external/downloads/999";
        selections.retainValid(Arrays.asList(one, two), statuses);
        assertEquals(0, selections.size());
        assertEquals(DownloadService.State.DONE, two.state);
    }

    @Test public void safeBulkShareUsesOnlyReadGrantsForEveryUri() {
        final DownloadService.Task one = saved(
                "content://media/external/downloads/31", "video/mp4");
        final DownloadService.Task two = saved(
                "content://media/external/audio/media/32", "audio/mpeg");
        final Intent sharing = LibraryBatchActions.shareIntent(Arrays.asList(one, two));
        assertNotNull(sharing);
        assertEquals(Intent.ACTION_SEND_MULTIPLE, sharing.getAction());
        assertEquals("*/*", sharing.getType());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                sharing.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, sharing.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertEquals(2, sharing.getClipData().getItemCount());
        assertEquals(Uri.parse(one.outputUri), sharing.getClipData().getItemAt(0).getUri());
        assertEquals(Uri.parse(two.outputUri), sharing.getClipData().getItemAt(1).getUri());
    }

    @Test public void sharingRejectsUnsafeOrEmptyInputs() {
        final DownloadService.Task first = saved(
                "content://media/external/downloads/41", "video/mp4");
        final DownloadService.Task unsafe = saved("file:///sdcard/movie.mp4", "video/mp4");
        assertNull(LibraryBatchActions.shareIntent(null));
        assertNull(LibraryBatchActions.shareIntent(Collections.emptyList()));
        assertNull(LibraryBatchActions.shareIntent(Arrays.asList(first, unsafe)));
        assertEquals(Intent.ACTION_SEND, LibraryBatchActions.shareIntent(
                Collections.singletonList(first)).getAction());
        assertEquals(DownloadService.State.DONE, first.state);
    }
}
