package com.example.ytdl;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class QueueActionStateTest {
    private static DownloadService.Task task(DownloadService.State state, boolean pauseRequested) {
        final DownloadService.Task task = new DownloadService.Task(
                "https://example.org/media", "sample", DownloadService.Spec.muxed(720, "720p"));
        task.state = state;
        task.pauseRequested = pauseRequested;
        return task;
    }

    @Test public void resumePausedTaskQueuesIt() {
        final DownloadService.Task task = task(DownloadService.State.PAUSED, true);
        assertTrue(DownloadService.applyResumeRequest(task));
        assertEquals(DownloadService.State.QUEUED, task.state);
        assertFalse(task.pauseRequested);
    }

    @Test public void resumeActiveTaskClearsPendingPauseWithoutDuplicatingWork() {
        final DownloadService.Task task = task(DownloadService.State.VIDEO, true);
        assertFalse(DownloadService.applyResumeRequest(task));
        assertEquals(DownloadService.State.VIDEO, task.state);
        assertFalse(task.pauseRequested);
    }

    @Test public void resumeActiveTaskWithoutPauseIsNoOp() {
        final DownloadService.Task task = task(DownloadService.State.RESOLVING, false);
        assertFalse(DownloadService.applyResumeRequest(task));
        assertEquals(DownloadService.State.RESOLVING, task.state);
    }

    @Test public void resumeQueuedTaskDoesNotDuplicateIt() {
        final DownloadService.Task task = task(DownloadService.State.QUEUED, false);
        assertFalse(DownloadService.applyResumeRequest(task));
        assertEquals(DownloadService.State.QUEUED, task.state);
    }

    @Test public void resumeFinishedTaskDoesNotRequeueIt() {
        for (final DownloadService.State state : new DownloadService.State[]{
                DownloadService.State.DONE, DownloadService.State.FAILED,
                DownloadService.State.CANCELLED}) {
            final DownloadService.Task task = task(state, false);
            assertFalse(DownloadService.applyResumeRequest(task));
            assertEquals(state, task.state);
        }
    }

    @Test public void resumeNullTaskIsSafe() {
        assertFalse(DownloadService.applyResumeRequest(null));
    }
}
