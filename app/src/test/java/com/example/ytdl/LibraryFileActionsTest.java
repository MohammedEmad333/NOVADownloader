package com.example.ytdl;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LibraryFileActionsTest {
    private static DownloadService.Task saved(String uri, String mime) {
        final DownloadService.Task task = new DownloadService.Task(
                "https://youtu.be/sample", "Saved file",
                DownloadService.Spec.muxed(720, "720p"));
        task.state = DownloadService.State.DONE;
        task.outputUri = uri;
        task.mimeType = mime;
        return task;
    }

    @Test public void completedMediaStoreFilesCanOpenWithReadPermission() {
        final DownloadService.Task task = saved(
                "content://media/external/video/media/125", "VIDEO/MP4");
        final Intent view = LibraryFileActions.viewIntent(task);
        assertNotNull(view);
        assertEquals(Intent.ACTION_VIEW, view.getAction());
        assertEquals(Uri.parse(task.outputUri), view.getData());
        assertEquals("video/mp4", view.getType());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                view.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, view.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Test public void sharingGrantsReadAccessToTheExactSavedUri() {
        final DownloadService.Task task = saved(
                "content://media/external/downloads/126", "audio/mpeg");
        final Intent share = LibraryFileActions.shareIntent(task);
        assertNotNull(share);
        assertEquals(Intent.ACTION_SEND, share.getAction());
        assertEquals("audio/mpeg", share.getType());
        assertEquals(Uri.parse(task.outputUri), share.getParcelableExtra(Intent.EXTRA_STREAM));
        final ClipData clip = share.getClipData();
        assertNotNull(clip);
        assertEquals(1, clip.getItemCount());
        assertEquals(Uri.parse(task.outputUri), clip.getItemAt(0).getUri());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, share.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Test public void remoteUntrustedAndMalformedReferencesCannotLeaveTheLibrary() {
        final String[] bad = {
                "https://example.org/video.mp4",
                "file:///sdcard/Movies/video.mp4",
                "content://other.example/external/video/media/52",
                "content://media/external/video/media/52?tracking=1",
                "content://media/external/video/media/52#preview",
                "content://media/external/video/media/0",
                "content://media/external/video/media/not-a-number"
        };
        for (String uri : bad) {
            final DownloadService.Task task = saved(uri, "video/mp4");
            assertFalse(uri, LibraryFileActions.eligible(task));
            assertNull(uri, LibraryFileActions.viewIntent(task));
            assertNull(uri, LibraryFileActions.shareIntent(task));
        }
    }

    @Test public void unfinishedAndMissingSavedFilesNeverOpenOrShare() {
        final DownloadService.Task task = saved(
                "content://media/external/audio/media/127", "audio/mpeg");
        task.state = DownloadService.State.FAILED;
        assertNull(LibraryFileActions.viewIntent(task));
        assertNull(LibraryFileActions.shareIntent(task));
        task.state = DownloadService.State.PAUSED;
        assertNull(LibraryFileActions.shareIntent(task));
        task.state = DownloadService.State.DONE;
        task.outputUri = null;
        assertNull(LibraryFileActions.viewIntent(task));
        assertNull(LibraryFileActions.shareIntent(task));
        assertNull(LibraryFileActions.viewIntent(null));
        assertNull(LibraryFileActions.shareIntent(null));
    }

    @Test public void invalidMimeMetadataUsesGenericBinaryType() {
        assertEquals("application/octet-stream", LibraryFileActions.safeMimeType(null));
        assertEquals("application/octet-stream", LibraryFileActions.safeMimeType(""));
        assertEquals("application/octet-stream", LibraryFileActions.safeMimeType("video/mp4; evil=1"));
        assertEquals("application/octet-stream", LibraryFileActions.safeMimeType("bad mime"));
        assertEquals("video/mp4", LibraryFileActions.safeMimeType(" Video/MP4 "));
        final DownloadService.Task task = saved(
                "content://media/external/downloads/128", null);
        assertEquals("application/octet-stream", LibraryFileActions.viewIntent(task).getType());
        assertEquals("application/octet-stream", LibraryFileActions.shareIntent(task).getType());
    }
}
