package com.example.ytdl;

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LocalVideoImportTest {
    @Test public void acceptsOnlyExplicitSupportedVideoMimeTypes() {
        assertTrue(LocalVideoImport.supportedMime("video/mp4"));
        assertTrue(LocalVideoImport.supportedMime("VIDEO/WEBM"));
        assertTrue(LocalVideoImport.supportedMime("video/3gpp"));
        assertTrue(LocalVideoImport.supportedMime("video/quicktime"));
        assertTrue(LocalVideoImport.supportedMime("video/x-matroska"));
        assertFalse(LocalVideoImport.supportedMime("application/octet-stream"));
        assertFalse(LocalVideoImport.supportedMime("image/jpeg"));
        assertFalse(LocalVideoImport.supportedMime("video/*"));
        assertFalse(LocalVideoImport.supportedMime(null));
    }

    @Test public void sanitizesPickedNamesWithoutTrustingPaths() {
        assertEquals("NOVA-import.mp4", LocalVideoImport.safeName("", "video/mp4"));
        assertEquals("__secret.mp4", LocalVideoImport.safeName("../secret", "video/mp4"));
        assertEquals("movie.webm", LocalVideoImport.safeName("movie", "video/webm"));
        assertEquals("clip.mp4", LocalVideoImport.safeName("clip.mp4", "video/mp4"));
        assertEquals("one_two.mp4", LocalVideoImport.safeName("one/two", "video/mp4"));
        assertTrue(LocalVideoImport.safeName("a".repeat(500), "video/mp4").length() <= 125);
    }

    @Test public void pendingLedgerRecognizesImportedVideoRows() {
        assertTrue(PendingMediaLedger.isOwnedItemUri(
                Uri.parse("content://media/external/video/media/123")));
        assertFalse(PendingMediaLedger.isOwnedItemUri(
                Uri.parse("content://evil/external/video/media/123")));
        assertFalse(PendingMediaLedger.isOwnedItemUri(
                Uri.parse("content://media/external/video/media/0")));
    }

    private static View findText(View node, String text) {
        if (node instanceof TextView && text.contentEquals(((TextView) node).getText())) {
            return node;
        }
        // Explore controls live inside the ListView's non-selectable header, which
        // Robolectric may keep in the adapter until a real layout pass occurs.
        if (node instanceof android.widget.ListView) {
            final android.widget.ListView list = (android.widget.ListView) node;
            for (int i = 0; i < list.getHeaderViewsCount(); i++) {
                final View header = list.getAdapter().getView(i, null, list);
                final View found = findText(header, text);
                if (found != null) return found;
            }
        }
        if (node instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void historyImportButtonLaunchesAndroidDocumentPicker() {
        final HistoryActivity activity = Robolectric.buildActivity(HistoryActivity.class)
                .create().start().resume().get();
        final View button = findText(activity.findViewById(android.R.id.content),
                "＋ Import a video from your device");
        assertNotNull(button);
        button.performClick();
        final android.content.Intent intent = org.robolectric.Shadows.shadowOf(activity)
                .getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.getAction());
        assertEquals("video/*", intent.getType());
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
    }

    @Test public void exploreIncludesOfficialXOpenAndLocalImportActions() {
        final MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().get();
        assertNotNull(findText(activity.findViewById(android.R.id.content), "Open post in X"));
        assertNotNull(findText(activity.findViewById(android.R.id.content), "Import saved video"));
    }
}
