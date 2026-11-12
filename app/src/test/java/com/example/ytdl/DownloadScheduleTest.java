package com.example.ytdl;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class DownloadScheduleTest {
    @Test public void sameDayWindowIncludesStartAndExcludesEnd() {
        assertTrue(DownloadSchedule.insideWindow(600, 600, 900));
        assertTrue(DownloadSchedule.insideWindow(899, 600, 900));
        assertFalse(DownloadSchedule.insideWindow(900, 600, 900));
        assertFalse(DownloadSchedule.insideWindow(599, 600, 900));
    }

    @Test public void overnightWindowSpansMidnight() {
        assertTrue(DownloadSchedule.insideWindow(22 * 60, 22 * 60, 7 * 60));
        assertTrue(DownloadSchedule.insideWindow(5 * 60, 22 * 60, 7 * 60));
        assertFalse(DownloadSchedule.insideWindow(12 * 60, 22 * 60, 7 * 60));
        assertFalse(DownloadSchedule.insideWindow(7 * 60, 22 * 60, 7 * 60));
    }

    @Test public void invalidAndZeroLengthWindowsAreRejected() {
        assertFalse(DownloadSchedule.validMinute(-1));
        assertFalse(DownloadSchedule.validMinute(1440));
        assertFalse(DownloadSchedule.insideWindow(600, 600, 600));
        assertFalse(DownloadSchedule.insideWindow(500, -1, 700));
    }

    @Test public void scheduleReminderIsRateLimitedToTwelveHours() {
        final long now = 1_900_000_000_000L;
        assertTrue(ScheduleReminderWorker.shouldNotify(now, 0,
                true, true, false));
        assertFalse(ScheduleReminderWorker.shouldNotify(now,
                now - 20 * 60 * 1000L, true, true, false));
        assertFalse(ScheduleReminderWorker.shouldNotify(now, 0,
                false, true, false));
        assertFalse(ScheduleReminderWorker.shouldNotify(now, 0,
                true, true, true));
        assertTrue(ScheduleReminderWorker.shouldNotify(now,
                now - 13 * 60 * 60 * 1000L, true, true, false));
    }

    @Test public void clockLabelsPreserveHourAndMinute() {
        assertEquals("00:00", DownloadSchedule.clockLabel(0));
        assertEquals("23:59", DownloadSchedule.clockLabel(1439));
    }
}
