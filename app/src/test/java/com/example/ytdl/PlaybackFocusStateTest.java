package com.example.ytdl;

import android.media.AudioManager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PlaybackFocusStateTest {
    @Test public void transientLossResumesOnlyPreviouslyPlayingAudio() {
        final PlaybackFocusState state = new PlaybackFocusState();
        assertEquals(PlaybackFocusState.Action.PAUSE,
                state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, true));
        assertEquals(PlaybackFocusState.Action.RESUME,
                state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN, false));
        assertEquals(PlaybackFocusState.Action.NONE,
                state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN, false));
    }

    @Test public void duckingInterruptionPausesAndCanResume() {
        final PlaybackFocusState state = new PlaybackFocusState();
        assertEquals(PlaybackFocusState.Action.PAUSE,
                state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, true));
        assertEquals(PlaybackFocusState.Action.RESUME,
                state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN, false));
    }

    @Test public void permanentLossNeverSchedulesAutomaticResume() {
        final PlaybackFocusState state = new PlaybackFocusState();
        assertEquals(PlaybackFocusState.Action.PAUSE,
                state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, true));
        assertEquals(PlaybackFocusState.Action.PAUSE,
                state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS, false));
        assertEquals(PlaybackFocusState.Action.NONE,
                state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN, false));
    }

    @Test public void interruptionWhilePausedDoesNotAutoResume() {
        final PlaybackFocusState state = new PlaybackFocusState();
        assertEquals(PlaybackFocusState.Action.PAUSE,
                state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, false));
        assertEquals(PlaybackFocusState.Action.NONE,
                state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN, false));
    }

    @Test public void explicitPauseAndHeadsetRemovalClearPendingResume() {
        final PlaybackFocusState state = new PlaybackFocusState();
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, true);
        state.onUserPause();
        assertEquals(PlaybackFocusState.Action.NONE,
                state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN, false));

        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, true);
        state.clear();
        assertEquals(PlaybackFocusState.Action.NONE,
                state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN, false));
    }
}
