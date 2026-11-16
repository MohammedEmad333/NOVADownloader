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
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;

/**
 * One-time tap-to-open reminder for the next eligible network. The worker never
 * starts a data-sync foreground service from the background.
 */
public final class NetworkRecoveryWorker extends Worker {
    private static final String WORK_NAME = "nova-network-recovery-reminder";
    private static final String CHANNEL = "nova-network-recovery";
    private static final String PREFS = "nova-network-recovery-reminders";
    private static final String LAST = "last_prompt";
    static final long MIN_REMINDER_GAP_MS = 30L * 60 * 1000;

    public NetworkRecoveryWorker(@NonNull Context context,
                                 @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    static boolean shouldNotify(long now, long last, boolean queued,
                                boolean allowed, boolean workerRunning) {
        return queued && allowed && !workerRunning && now >= last
                && now - last >= MIN_REMINDER_GAP_MS;
    }

    static void schedule(Context context) {
        final Context app = context.getApplicationContext();
        final Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(DownloadService.wifiOnly(app)
                        ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();
        final OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                NetworkRecoveryWorker.class).setConstraints(constraints).build();
        WorkManager.getInstance(app).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP, request);
    }

    @NonNull @Override public Result doWork() {
        final Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return Result.success();
        if (!DownloadConnectivity.connected(context)
                || (DownloadService.wifiOnly(context)
                        && !DownloadService.isWifiConnectedForUi(context))
                || DownloadSchedule.blockedReason(context) != null) {
            return Result.success();
        }
        DownloadService.ensureLoaded(context);
        boolean queued = false;
        for (DownloadService.Task item : DownloadService.snapshot()) {
            if (item.state == DownloadService.State.QUEUED
                    && !item.cancelled && !item.pauseRequested) {
                queued = true;
                break;
            }
        }
        final long now = System.currentTimeMillis();
        final android.content.SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        if (!shouldNotify(now, prefs.getLong(LAST, 0),
                queued, true, DownloadService.isWorkerRunning())) return Result.success();
        final NotificationManager notifications =
                context.getSystemService(NotificationManager.class);
        if (notifications == null) return Result.success();
        final NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Download connection reminders", NotificationManager.IMPORTANCE_DEFAULT);
        notifications.createNotificationChannel(channel);
        final Intent launch = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final PendingIntent open = PendingIntent.getActivity(context, 634, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final Notification note = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(NovaPreferences.text(context,
                        "NOVA downloads can resume", "يمكن استئناف تنزيلات نوفا"))
                .setContentText(NovaPreferences.text(context,
                        "Connection restored. Open NOVA to continue queued downloads.",
                        "عاد الاتصال. افتح نوفا لمتابعة التنزيلات في الطابور."))
                .setContentIntent(open).setAutoCancel(true).build();
        notifications.notify(634, note);
        prefs.edit().putLong(LAST, now).apply();
        return Result.success();
    }
}
