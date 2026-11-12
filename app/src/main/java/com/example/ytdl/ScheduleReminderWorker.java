package com.example.ytdl;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Persistent, short WorkManager wake-up. Android restricts starting a download FGS
 * from the background, so scheduled readiness is a tap-to-start notification.
 */
public final class ScheduleReminderWorker extends Worker {
    static final String CHANNEL = "nova-schedule-ready";
    static final String PREFS = "nova_schedule_reminder";
    static final String LAST = "last_prompt_time";
    static final long MIN_REMINDER_GAP_MS = 12L * 60 * 60 * 1000;

    public ScheduleReminderWorker(@NonNull Context context,
                                  @NonNull WorkerParameters params) {
        super(context, params);
    }

    static boolean shouldNotify(long now, long last, boolean queued,
                                boolean eligible, boolean workerActive) {
        return queued && eligible && !workerActive && now >= last
                && now - last >= MIN_REMINDER_GAP_MS;
    }

    @NonNull @Override public Result doWork() {
        final Context context = getApplicationContext();
        if (DownloadSchedule.blockedReason(context) != null) return Result.success();
        if (DownloadService.wifiOnly(context)
                && !DownloadService.isWifiConnectedForUi(context)) return Result.success();
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return Result.success();

        DownloadService.ensureLoaded(context);
        boolean queued = false;
        for (DownloadService.Task task : DownloadService.snapshot()) {
            if (task.state == DownloadService.State.QUEUED) {
                queued = true;
                break;
            }
        }
        final long now = System.currentTimeMillis();
        final android.content.SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!shouldNotify(now, preferences.getLong(LAST, 0), queued, true,
                DownloadService.isWorkerRunning())) return Result.success();

        final NotificationManager notifications =
                context.getSystemService(NotificationManager.class);
        if (notifications == null) return Result.success();
        final NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Scheduled download reminders", NotificationManager.IMPORTANCE_DEFAULT);
        notifications.createNotificationChannel(channel);

        final Intent launch = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final PendingIntent open = PendingIntent.getActivity(context, 619, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final Notification note = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(NovaPreferences.text(context,
                        "Your queued downloads are ready", "التنزيلات المجدولة جاهزة"))
                .setContentText(NovaPreferences.text(context,
                        "Tap to open NOVA and start your downloads.",
                        "افتح NOVA لبدء التنزيلات في الوقت المناسب."))
                .setContentIntent(open).setAutoCancel(true)
                .build();
        notifications.notify(619, note);
        preferences.edit().putLong(LAST, now).apply();
        return Result.success();
    }
}
