package com.example.ytdl;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class DownloadTimeoutPolicyTest {
    private Context context;
    private List<DownloadService.Task> tasks;

    @SuppressWarnings("unchecked")
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        final Field taskField = DownloadService.class.getDeclaredField("TASKS");
        taskField.setAccessible(true);
        tasks = (List<DownloadService.Task>) taskField.get(null);
        synchronized (tasks) {
            tasks.clear();
        }
        context.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)
                .edit().remove("fgs_resume_required").commit();
    }

    @After public void cleanup() {
        synchronized (tasks) {
            tasks.clear();
        }
        context.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)
                .edit().remove("fgs_resume_required").commit();
    }

    private DownloadService.Task task(DownloadService.State state) {
        final DownloadService.Task item = new DownloadService.Task(
                "https://example.org/public", "Video",
                DownloadService.Spec.muxed(720, "720p"));
        item.state = state;
        return item;
    }

    @Test public void onlyActiveIncompleteTaskIsMarkedForCooperativePause() {
        final DownloadService.Task active = task(DownloadService.State.VIDEO);
        DownloadTimeoutPolicy.requestPause(active);
        assertTrue(active.pauseRequested);
        assertEquals(DownloadService.State.VIDEO, active.state);
        final DownloadService.Task queued = task(DownloadService.State.QUEUED);
        DownloadTimeoutPolicy.requestPause(queued);
        assertFalse(queued.pauseRequested);
        final DownloadService.Task complete = task(DownloadService.State.DONE);
        DownloadTimeoutPolicy.requestPause(complete);
        assertFalse(complete.pauseRequested);
        assertEquals(DownloadService.State.DONE, complete.state);
        DownloadTimeoutPolicy.requestPause(null);
    }

    @Test public void explicitCancellationMustStillWinOverServiceTimeout() {
        final DownloadService.Task item = task(DownloadService.State.AUDIO);
        item.cancelled = true;
        DownloadTimeoutPolicy.requestPause(item);
        assertFalse(item.pauseRequested);
        assertEquals(DownloadService.State.CANCELLED,
                DownloadService.stateAfterUnexpectedFailure(item, false));
    }

    @Test public void timeoutFlagsCanBeReadAndDoesNotRemoveQueueEntries() throws Exception {
        final DownloadService.Task active = task(DownloadService.State.VIDEO);
        final DownloadService.Task queued = task(DownloadService.State.QUEUED);
        final DownloadService.Task saved = task(DownloadService.State.DONE);
        saved.outputUri = "content://media/external/downloads/91";
        synchronized (tasks) {
            tasks.add(active);
            tasks.add(queued);
            tasks.add(saved);
        }

        final ServiceController<DownloadService> serviceController =
                Robolectric.buildService(DownloadService.class).create();
        final DownloadService service = serviceController.get();
        final Field processing = DownloadService.class.getDeclaredField("processingTask");
        processing.setAccessible(true);
        processing.set(service, active);
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        assertTrue(DownloadService.requiresForegroundResume(context));
        assertTrue(active.pauseRequested);
        assertEquals(DownloadService.State.QUEUED, queued.state);
        assertEquals(DownloadService.State.DONE, saved.state);
        assertEquals("content://media/external/downloads/91", saved.outputUri);
        assertEquals(3, DownloadService.snapshot().size());
        final Field stopped = DownloadService.class.getDeclaredField("timeoutRequested");
        stopped.setAccessible(true);
        assertTrue(stopped.getBoolean(service));
        assertEquals(Service.START_NOT_STICKY,
                service.onStartCommand(new Intent(), 0, 2));
        serviceController.destroy();
    }

    @Test public void activeEntriesRemainRecoverableAcrossProcessRestart() throws Exception {
        final DownloadService.Task active = task(DownloadService.State.VIDEO);
        DownloadTimeoutPolicy.requestPause(active);
        final org.json.JSONArray records = new org.json.JSONArray();
        records.put(active.toJson());
        final DownloadService.RestoredQueue restored = DownloadService.decodeQueue(records);
        assertEquals(1, restored.tasks.size());
        assertEquals(DownloadService.State.PAUSED, restored.tasks.get(0).state);
        assertFalse(restored.tasks.get(0).cancelled);
    }
}
