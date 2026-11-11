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
