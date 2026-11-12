package com.example.ytdl;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class NovaPreferencesTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Test public void languageCanBeChangedAndRestored() {
        final String original = NovaPreferences.languageChoice(context);
        try {
            NovaPreferences.setLanguage(context, "ar");
            assertTrue(NovaPreferences.arabic(context));
            assertEquals("مرحباً", NovaPreferences.text(context, "Hello", "مرحباً"));
            NovaPreferences.setLanguage(context, "en");
            assertFalse(NovaPreferences.arabic(context));
            assertEquals("Hello", NovaPreferences.text(context, "Hello", "مرحباً"));
        } finally {
            NovaPreferences.setLanguage(context, original);
        }
    }

    @Test public void defaultQualitiesPersistAndAffectOnlyRank() {
        final int oldVideo = NovaPreferences.videoHeight(context);
        final int oldMp3 = NovaPreferences.mp3Bitrate(context);
        try {
            NovaPreferences.setVideoHeight(context, 1080);
            NovaPreferences.setMp3Bitrate(context, 320);
            assertEquals(1080, NovaPreferences.videoHeight(context));
            assertEquals(320, NovaPreferences.mp3Bitrate(context));
            assertEquals(0, NovaPreferences.formatPriority(context,
                    DownloadService.Kind.MERGE, 1080, 0));
            assertEquals(0, NovaPreferences.formatPriority(context,
                    DownloadService.Kind.MP3, 0, 320));
            assertEquals(1, NovaPreferences.formatPriority(context,
                    DownloadService.Kind.MUXED, 480, 0));
            assertEquals(1, NovaPreferences.formatPriority(context,
                    DownloadService.Kind.AUDIO, 0, 0));
        } finally {
            NovaPreferences.setVideoHeight(context, oldVideo);
            NovaPreferences.setMp3Bitrate(context, oldMp3);
        }
    }

    @Test public void invalidQualityIsRejected() {
        assertFalse(NovaPreferences.validVideoHeight(999));
        assertFalse(NovaPreferences.validMp3Rate(16));
        try {
            NovaPreferences.setVideoHeight(context, 999);
            fail("Unexpected quality accepted");
        } catch (IllegalArgumentException expected) {}
    }

    @Test public void settingsActivityCreatesWithoutExternalPermissions() {
        final SettingsActivity activity = org.robolectric.Robolectric
                .buildActivity(SettingsActivity.class).create().start().resume().get();
        assertNotNull(activity.findViewById(android.R.id.content));
    }
}
