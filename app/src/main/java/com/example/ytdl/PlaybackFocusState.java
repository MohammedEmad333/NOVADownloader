package com.example.ytdl;

import android.media.AudioManager;

/**
 * Deterministic audio-focus transition state for the local audio player.
 * Only a transient interruption of active playback is eligible for auto-resume.
 */
final class PlaybackFocusState {
    enum Action { NONE, PAUSE, RESUME }

    private boolean resumeAfterTransientLoss;

    Action onFocusChange(int change, boolean currentlyPlaying) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (!resumeAfterTransientLoss) return Action.NONE;
            resumeAfterTransientLoss = false;
            return Action.RESUME;
        }
        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            resumeAfterTransientLoss = currentlyPlaying;
            return Action.PAUSE;
        }
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeAfterTransientLoss = false;
            return Action.PAUSE;
        }
        return Action.NONE;
    }

    void onUserPause() {
        resumeAfterTransientLoss = false;
    }

    void clear() {
        resumeAfterTransientLoss = false;
    }
}
