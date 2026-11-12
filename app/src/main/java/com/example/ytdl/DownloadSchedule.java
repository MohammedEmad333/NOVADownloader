package com.example.ytdl;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * Optional download policy. Local times are interpreted in the device's time zone.
 * The queue only transfers while its conditions hold; periodic background work
 * reminds users to open NOVA rather than attempting a forbidden FGS start.
 */
public final class DownloadSchedule {
    private static final String PREFS = "nova_download_schedule";
    private static final String CHARGING = "charging_only";
    private static final String WINDOW = "daily_window_enabled";
    private static final String START = "window_start_minute";
    private static final String END = "window_end_minute";
    private static final String REMINDER = "nova-scheduled-downloads-ready";
    private static final int DEFAULT_START = 22 * 60;
    private static final int DEFAULT_END = 7 * 60;

    private DownloadSchedule() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean chargingOnly(Context context) {
        return prefs(context).getBoolean(CHARGING, false);
    }

    public static void setChargingOnly(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(CHARGING, enabled).apply();
        refreshReminder(context);
        DownloadService.pokeIfReady(context);
    }

    public static boolean windowEnabled(Context context) {
        return prefs(context).getBoolean(WINDOW, false);
    }

    public static int windowStart(Context context) {
        return prefs(context).getInt(START, DEFAULT_START);
    }

    public static int windowEnd(Context context) {
        return prefs(context).getInt(END, DEFAULT_END);
    }

    public static void setWindow(Context context, boolean enabled, int start, int end) {
        if (!validMinute(start) || !validMinute(end) || (enabled && start == end)) {
            throw new IllegalArgumentException("Invalid daily download window");
        }
        prefs(context).edit()
                .putBoolean(WINDOW, enabled)
                .putInt(START, start).putInt(END, end).apply();
        refreshReminder(context);
        DownloadService.pokeIfReady(context);
    }

    public static boolean validMinute(int minute) {
        return minute >= 0 && minute < 24 * 60;
    }

    static boolean insideWindow(int now, int start, int end) {
        if (!validMinute(now) || !validMinute(start) || !validMinute(end) || start == end) {
            return false;
        }
        return start < end
                ? now >= start && now < end
                : now >= start || now < end;
    }

    static int minutesOfDay(long millis) {
        final Calendar time = Calendar.getInstance();
        time.setTimeInMillis(millis);
        return time.get(Calendar.HOUR_OF_DAY) * 60 + time.get(Calendar.MINUTE);
    }

    public static String blockedReason(Context context) {
        if (chargingOnly(context) && !isPluggedIn(context)) return "Waiting for charging";
        if (windowEnabled(context) && !insideWindow(
                minutesOfDay(System.currentTimeMillis()),
                windowStart(context), windowEnd(context))) {
            return "Waiting for scheduled time";
        }
        return null;
    }

    private static boolean isPluggedIn(Context context) {
        final Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    static String clockLabel(int minute) {
        return String.format(java.util.Locale.US, "%02d:%02d", minute / 60, minute % 60);
    }

    /** Only schedule if the user explicitly enables a non-default time/power policy. */
    public static void refreshReminder(Context context) {
        final Context app = context.getApplicationContext();
        final WorkManager manager = WorkManager.getInstance(app);
        if (!chargingOnly(app) && !windowEnabled(app)) {
            manager.cancelUniqueWork(REMINDER);
            return;
        }

        final Constraints constraints = new Constraints.Builder()
                .setRequiresCharging(chargingOnly(app))
                .setRequiredNetworkType(DownloadService.wifiOnly(app)
                        ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();
        final PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(ScheduleReminderWorker.class, 15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build();
        manager.enqueueUniquePeriodicWork(REMINDER, ExistingPeriodicWorkPolicy.UPDATE, request);
    }
}
