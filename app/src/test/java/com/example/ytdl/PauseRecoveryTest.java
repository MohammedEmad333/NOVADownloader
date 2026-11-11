package com.example.ytdl;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PauseRecoveryTest {
    private Context context;
    private List<DownloadService.Task> tasks;

    @SuppressWarnings("unchecked")
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        final Field field = DownloadService.class.getDeclaredField("TASKS");
        field.setAccessible(true);
        tasks = (List<DownloadService.Task>) field.get(null);
        synchronized (tasks) {
            tasks.clear();
        }
        context.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)
                .edit().remove("pause_after_current").commit();
    }

    @After public void cleanup() {
        synchronized (tasks) {
            tasks.clear();
        }
        context.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)
                .edit().remove("pause_after_current").commit();
    }

    @Test public void persistedPauseRequestPausesAllRemainingQueuedTasks() {
        final DownloadService.Task queued = new DownloadService.Task(
                "https://example.com/one", "One", DownloadService.Spec.muxed(720, "720p"));
        final DownloadService.Task alreadyPaused = new DownloadService.Task(
                "https://example.com/two", "Two", DownloadService.Spec.muxed(720, "720p"));
        alreadyPaused.state = DownloadService.State.PAUSED;
        tasks.add(queued);
        tasks.add(alreadyPaused);
        context.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("pause_after_current", true).commit();

        assertTrue(DownloadService.restorePauseAfterCurrent(context));
        assertEquals(DownloadService.State.PAUSED, queued.state);
        assertTrue(queued.pauseRequested);
        assertEquals(DownloadService.State.PAUSED, alreadyPaused.state);
        assertFalse(context.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)
                .getBoolean("pause_after_current", true));
        assertFalse(DownloadService.restorePauseAfterCurrent(context));
    }

    @Test public void noPendingRequestLeavesQueueRunnable() {
        final DownloadService.Task queued = new DownloadService.Task(
                "https://example.com/three", "Three", DownloadService.Spec.muxed(360, "360p"));
        tasks.add(queued);
        assertFalse(DownloadService.restorePauseAfterCurrent(context));
        assertEquals(DownloadService.State.QUEUED, queued.state);
    }
}
