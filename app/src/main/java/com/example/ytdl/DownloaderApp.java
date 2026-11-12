package com.example.ytdl;

import android.app.Application;

/**
 * Process bootstrap. Screen styling is now applied by the screens themselves
 * using Ui components, rather than mutating view hierarchies after rendering.
 */
public final class DownloaderApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Net.ensureExtractor();
    }
}
