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
public final class PlaybackSpeedTest {
    @Test public void supportsBoundedRatesAndCycles() {
        assertEquals(1.0f, PlaybackSpeed.value(-1), 0.001f);
        assertEquals(1.0f, PlaybackSpeed.value(99), 0.001f);
        assertEquals(0.75f, PlaybackSpeed.value(0), 0.001f);
        assertEquals(1.25f, PlaybackSpeed.value(2), 0.001f);
        assertEquals(1.5f, PlaybackSpeed.value(3), 0.001f);
        assertEquals(2.0f, PlaybackSpeed.value(4), 0.001f);
        assertEquals("1.25x", PlaybackSpeed.label(2));
        assertEquals("1x", PlaybackSpeed.label(1));
        assertEquals(0, PlaybackSpeed.next(4));
    }

    @Test public void storesUserSpeedAcrossPlayerScreens() {
        final Context context = RuntimeEnvironment.getApplication();
        try {
            PlaybackSpeed.save(context, 3);
            assertEquals(3, PlaybackSpeed.load(context));
            PlaybackSpeed.save(context, 0);
            assertEquals(0, PlaybackSpeed.load(context));
            try {
                PlaybackSpeed.save(context, 12);
                fail("Invalid playback speed saved");
            } catch (IllegalArgumentException expected) { }
            assertEquals(0, PlaybackSpeed.load(context));
        } finally {
            PlaybackSpeed.save(context, PlaybackSpeed.DEFAULT);
        }
    }

    @Test public void pipNeverAppliesToAudioOrUnpreparedVideo() {
        assertFalse(PlaybackSpeed.canEnterPip(false, true, true));
        assertFalse(PlaybackSpeed.canEnterPip(true, false, true));
        assertFalse(PlaybackSpeed.canEnterPip(true, true, false));
        assertTrue(PlaybackSpeed.canEnterPip(true, true, true));
    }
}
