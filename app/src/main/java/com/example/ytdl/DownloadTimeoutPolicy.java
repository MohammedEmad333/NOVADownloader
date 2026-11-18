package com.example.ytdl;

/**
 * Foreground-service timeout decisions independent of Android service lifecycle.
 * A completed/cancelled file stays untouched. Queued work is left available for
 * a later user-initiated foreground session; active work pauses cooperatively.
 */
final class DownloadTimeoutPolicy {
    private DownloadTimeoutPolicy() {}

    static void requestPause(DownloadService.Task inFlight) {
        if (inFlight == null || inFlight.cancelled || !inFlight.state.active()) return;
        inFlight.pauseRequested = true;
    }
}
