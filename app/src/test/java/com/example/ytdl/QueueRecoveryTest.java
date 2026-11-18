package com.example.ytdl;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class QueueRecoveryTest {
    private static JSONObject task(final long id, final String state) throws Exception {
        final DownloadService.Task item = new DownloadService.Task(
                "https://example.com/watch?v=" + id, "Item " + id,
                DownloadService.Spec.muxed(720, "720p"));
        final JSONObject record = item.toJson();
        record.put("id", id);
        record.put("state", state);
        return record;
    }

    @Test public void oneBrokenEntryDoesNotEraseGoodTasks() throws Exception {
        final JSONArray array = new JSONArray();
        array.put(task(2, "PAUSED"));
        array.put(task(100, "STATE_THAT_DOES_NOT_EXIST"));
        array.put(task(3, "DONE"));
        array.put(new JSONObject().put("title", "missing id"));

        final DownloadService.RestoredQueue restored = DownloadService.decodeQueue(array);
        assertEquals(2, restored.tasks.size());
        assertEquals(2L, restored.tasks.get(0).id);
        assertEquals(3L, restored.tasks.get(1).id);
        assertEquals(101L, restored.nextId);
    }

    @Test public void duplicateIdIsRestoredOnlyOnce() throws Exception {
        final JSONArray array = new JSONArray();
        array.put(task(7, "PAUSED"));
        array.put(task(7, "DONE"));

        final DownloadService.RestoredQueue restored = DownloadService.decodeQueue(array);
        assertEquals(1, restored.tasks.size());
        assertEquals(7L, restored.tasks.get(0).id);
        assertEquals(8L, restored.nextId);
    }

    @Test public void interruptedTaskIsPausedOnRestore() throws Exception {
        final JSONArray array = new JSONArray();
        array.put(task(21, "VIDEO"));

        final DownloadService.RestoredQueue restored = DownloadService.decodeQueue(array);
        assertEquals(1, restored.tasks.size());
        assertEquals(DownloadService.State.PAUSED, restored.tasks.get(0).state);
    }

    @Test public void queuedWorkSurvivesRestartWhileActiveTaskRequiresManualResume()
            throws Exception {
        final JSONArray input = new JSONArray();
        input.put(task(40, "VIDEO"));
        input.put(task(41, "QUEUED"));
        input.put(task(42, "PAUSED"));
        final DownloadService.RestoredQueue restored = DownloadService.decodeQueue(input);
        assertEquals(3, restored.tasks.size());
        assertEquals(DownloadService.State.PAUSED, restored.tasks.get(0).state);
        assertEquals(DownloadService.State.QUEUED, restored.tasks.get(1).state);
        assertEquals(DownloadService.State.PAUSED, restored.tasks.get(2).state);
        assertEquals(43L, restored.nextId);
    }

    @Test public void offlineErrorsRequeueAndRespectUserActions() {
        final DownloadService.Task item = new DownloadService.Task(
                "https://example.com/watch", "Sample",
                DownloadService.Spec.muxed(720, "720p"));
        assertEquals(DownloadService.State.QUEUED,
                DownloadService.stateAfterUnexpectedFailure(item, false));
        assertEquals(DownloadService.State.FAILED,
                DownloadService.stateAfterUnexpectedFailure(item, true));
        item.pauseRequested = true;
        assertEquals(DownloadService.State.PAUSED,
                DownloadService.stateAfterUnexpectedFailure(item, false));
        item.cancelled = true;
        assertEquals(DownloadService.State.CANCELLED,
                DownloadService.stateAfterUnexpectedFailure(item, false));
    }

    @Test public void rejectedResumePreservesBytesUntilExplicitRetry() {
        assertTrue(DownloadService.keepTempFilesAfter(DownloadService.State.FAILED, true));
        assertTrue(DownloadService.keepTempFilesAfter(DownloadService.State.PAUSED, false));
        assertTrue(DownloadService.keepTempFilesAfter(DownloadService.State.QUEUED, false));
        assertFalse(DownloadService.keepTempFilesAfter(DownloadService.State.FAILED, false));
        assertFalse(DownloadService.keepTempFilesAfter(DownloadService.State.DONE, false));
        assertFalse(DownloadService.keepTempFilesAfter(DownloadService.State.CANCELLED, false));
    }

    @Test public void badOrOverflowingIdsAreRejected() throws Exception {
        final JSONArray array = new JSONArray();
        array.put(task(-1, "DONE"));
        array.put(task(Long.MAX_VALUE, "DONE"));
        array.put(task(5, "QUEUED"));

        final DownloadService.RestoredQueue restored = DownloadService.decodeQueue(array);
        assertEquals(1, restored.tasks.size());
        assertEquals(5L, restored.tasks.get(0).id);
        assertEquals(6L, restored.nextId);
    }
}
