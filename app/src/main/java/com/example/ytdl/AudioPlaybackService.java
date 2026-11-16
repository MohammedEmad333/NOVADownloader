package com.example.ytdl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.IOException;
import java.util.Locale;

/**
 * User-started foreground service for locally saved audio only.
 * Playback belongs to this service, never to the Activity lifecycle.
 * No background autoplay is triggered on boot, network restoration or app startup.
 */
public final class AudioPlaybackService extends Service {
    static final String ACTION_START = "com.example.ytdl.audio.START";
    static final String ACTION_TOGGLE = "com.example.ytdl.audio.TOGGLE";
    static final String ACTION_STOP = "com.example.ytdl.audio.STOP";
    private static final String EXTRA_ID = "task_id";
    private static final String EXTRA_POSITION = "start_ms";
    private static final String EXTRA_AUTOPLAY = "start_playing";
    private static final String CHANNEL = "nova_local_audio_playback";
    private static final int NOTIFICATION_ID = 61;

    private static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();

    static final class Snapshot {
        final long taskId;
        final boolean prepared;
        final boolean playing;
        final int position;
        final int duration;
        final int speedIndex;
        final String error;

        Snapshot(long taskId, boolean prepared, boolean playing, int position, int duration,
                 int speedIndex, String error) {
            this.taskId = taskId;
            this.prepared = prepared;
            this.playing = playing;
            this.position = position;
            this.duration = duration;
            this.speedIndex = speedIndex;
            this.error = error;
        }
    }

    final class LocalBinder extends Binder {
        AudioPlaybackService service() { return AudioPlaybackService.this; }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaPlayer player;
    private DownloadService.Task task;
    private String taskUri;
    private boolean prepared;
    private boolean wantPlayback;
    private boolean foregroundStarted;
    private boolean resumeAfterTransientFocusLoss;
    private int startPosition;
    private int speedIndex = PlaybackSpeed.DEFAULT;
    private String error;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeAfterTransientFocusLoss) {
                resumeAfterTransientFocusLoss = false;
                setPlaying(true);
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            resumeAfterTransientFocusLoss = isPlaying();
            pausePlayback(false);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeAfterTransientFocusLoss = false;
            pausePlayback(false);
        }
    };

    private final BroadcastReceiver unplugged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null && AudioManager.ACTION_AUDIO_BECOMING_NOISY
                    .equals(intent.getAction())) {
                resumeAfterTransientFocusLoss = false;
                pausePlayback(false);
            }
        }
    };

    private final Runnable persistWhilePlaying = new Runnable() {
        @Override public void run() {
            savePosition();
            if (isPlaying()) handler.postDelayed(this, 10_000);
        }
    };

    static boolean eligible(DownloadService.Task item) {
        return LocalMediaPlayerActivity.canPlay(item)
                && item.mimeType.toLowerCase(Locale.ROOT).startsWith("audio/");
    }

    /** Only a saved task id is passed; no external URI or credentials enter this service. */
    static Intent startIntent(Context context, long taskId, int position, boolean autoplay) {
        if (taskId <= 0) throw new IllegalArgumentException("Invalid local track id");
        return new Intent(context, AudioPlaybackService.class).setAction(ACTION_START)
                .putExtra(EXTRA_ID, taskId)
                .putExtra(EXTRA_POSITION, Math.max(0, position))
                .putExtra(EXTRA_AUTOPLAY, autoplay);
    }

    private Intent command(String action) {
        return new Intent(this, AudioPlaybackService.class).setAction(action);
    }

    @Override public void onCreate() {
        super.onCreate();
        audioManager = getSystemService(AudioManager.class);
        if (audioManager != null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(ATTRIBUTES)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(focusListener).build();
        }
        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "Offline audio playback", NotificationManager.IMPORTANCE_LOW));
        }
        final IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(unplugged, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPlayback();
        } else if (ACTION_TOGGLE.equals(action)) {
            setPlaying(!isPlaying());
        } else if (ACTION_START.equals(action)) {
            startTrack(intent.getLongExtra(EXTRA_ID, -1),
                    Math.max(0, intent.getIntExtra(EXTRA_POSITION, 0)),
                    intent.getBooleanExtra(EXTRA_AUTOPLAY, true));
        } else {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    Snapshot snapshot() {
        return new Snapshot(task == null ? -1 : task.id, prepared, isPlaying(),
                currentPosition(), duration(), speedIndex, error);
    }

    private int currentPosition() {
        if (!prepared || player == null) return startPosition;
        try { return Math.max(0, player.getCurrentPosition()); }
        catch (IllegalStateException unavailable) { return startPosition; }
    }

    private int duration() {
        if (!prepared || player == null) return 0;
        try { return Math.max(0, player.getDuration()); }
        catch (IllegalStateException unavailable) { return 0; }
    }

    private boolean isPlaying() {
        if (!prepared || player == null) return false;
        try { return player.isPlaying(); }
        catch (IllegalStateException unavailable) { return false; }
    }

    private void startTrack(long taskId, int position, boolean autoplay) {
        DownloadService.ensureLoaded(this);
        final DownloadService.Task saved = DownloadService.byId(taskId);
        if (!eligible(saved)) {
            error = "This saved audio file is unavailable";
            stopPlayback();
            return;
        }
        if (task != null && task.id == saved.id && taskUri != null
                && taskUri.equals(saved.outputUri) && player != null) {
            // A new Activity after rotation must not reset a playing track.
            return;
        }
        releasePlayer();
        task = saved;
        taskUri = saved.outputUri;
        prepared = false;
        wantPlayback = autoplay;
        resumeAfterTransientFocusLoss = false;
        startPosition = position;
        speedIndex = PlaybackSpeed.load(this);
        error = null;

        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            foregroundStarted = true;
        } else {
            updateNotification();
        }

        try {
            player = new MediaPlayer();
            player.setAudioAttributes(ATTRIBUTES);
            player.setDataSource(this, Uri.parse(taskUri));
            final MediaPlayer active = player;
            player.setOnPreparedListener(ready -> {
                if (ready != active || player != ready) return;
                prepared = true;
                final int end = Math.max(0, ready.getDuration());
                if (startPosition > 0) ready.seekTo(
                        end > 0 ? Math.min(startPosition, end) : startPosition);
                if (wantPlayback) setPlaying(true);
                else updateNotification();
            });
            player.setOnCompletionListener(done -> {
                wantPlayback = false;
                resumeAfterTransientFocusLoss = false;
                startPosition = 0;
                LocalMediaPlayerActivity.rememberPosition(this, task, 0, 0);
                handler.removeCallbacks(persistWhilePlaying);
                updateNotification();
            });
            player.setOnErrorListener((failed, what, extra) -> {
                playbackError();
                return true;
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException failed) {
            playbackError();
        }
    }

    void setPlaying(boolean play) {
        wantPlayback = play;
        if (!prepared || player == null) {
            updateNotification();
            return;
        }
        try {
            if (play) {
                if (audioManager == null || focusRequest == null
                        || audioManager.requestAudioFocus(focusRequest)
                        != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    wantPlayback = false;
                    error = "Audio focus unavailable";
                    updateNotification();
                    return;
                }
                if (!player.isPlaying()) player.start();
                applySpeed();
                error = null;
                handler.removeCallbacks(persistWhilePlaying);
                handler.postDelayed(persistWhilePlaying, 10_000);
            } else {
                pausePlayback(true);
                return;
            }
            updateNotification();
        } catch (IllegalStateException failed) {
            playbackError();
        }
    }

    private void pausePlayback(boolean userInitiated) {
        wantPlayback = false;
        if (userInitiated) resumeAfterTransientFocusLoss = false;
        if (player != null && prepared) {
            try {
                if (player.isPlaying()) player.pause();
            } catch (IllegalStateException unavailable) {
                playbackError();
                return;
            }
        }
        handler.removeCallbacks(persistWhilePlaying);
        savePosition();
        updateNotification();
    }

    void seekTo(int desired) {
        if (!prepared || player == null || duration() <= 0) return;
        try {
            player.seekTo(Math.max(0, Math.min(duration(), desired)));
            savePosition();
        } catch (IllegalStateException failed) {
            playbackError();
        }
    }

    void setSpeedIndex(int index) {
        if (index != PlaybackSpeed.normalize(index)) return;
        speedIndex = index;
        PlaybackSpeed.save(this, index);
        if (isPlaying()) applySpeed();
        updateNotification();
    }

    private void applySpeed() {
        if (!prepared || player == null || !player.isPlaying()) return;
        try {
            final PlaybackParams params = player.getPlaybackParams()
                    .setSpeed(PlaybackSpeed.value(speedIndex));
            player.setPlaybackParams(params);
        } catch (RuntimeException unsupported) {
            error = "Playback speed is not supported by this file";
        }
    }

    private void playbackError() {
        error = "NOVA cannot play this saved audio file";
        wantPlayback = false;
        handler.removeCallbacks(persistWhilePlaying);
        releasePlayer();
        updateNotification();
    }

    private void savePosition() {
        if (task == null || !prepared) return;
        LocalMediaPlayerActivity.rememberPosition(this, task, currentPosition(), duration());
    }

    private void releasePlayer() {
        handler.removeCallbacks(persistWhilePlaying);
        savePosition();
        if (player != null) {
            player.release();
            player = null;
        }
        prepared = false;
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    private void updateNotification() {
        if (foregroundStarted && task != null) {
            final NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification());
        }
    }

    private Notification notification() {
        final boolean playing = isPlaying();
        final Intent open = new Intent(this, LocalMediaPlayerActivity.class)
                .putExtra(LocalMediaPlayerActivity.EXTRA_TASK_ID, task == null ? -1 : task.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final PendingIntent content = PendingIntent.getActivity(this, 61, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final PendingIntent toggle = PendingIntent.getService(this, 62,
                command(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE
                        | PendingIntent.FLAG_UPDATE_CURRENT);
        final PendingIntent stop = PendingIntent.getService(this, 63,
                command(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
                        | PendingIntent.FLAG_UPDATE_CURRENT);
        final String title = task == null || task.title == null ? "NOVA Audio" : task.title;
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(NovaPreferences.text(this,
                        playing ? "Playing saved audio" : "Audio paused",
                        playing ? "تشغيل صوت محفوظ" : "الصوت متوقف مؤقتًا"))
                .setContentIntent(content)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(playing ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play,
                        NovaPreferences.text(this, playing ? "Pause" : "Play",
                                playing ? "إيقاف مؤقت" : "تشغيل"), toggle)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        NovaPreferences.text(this, "Stop", "إيقاف"), stop)
                .build();
    }

    void stopPlayback() {
        releasePlayer();
        task = null;
        taskUri = null;
        wantPlayback = false;
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }
        stopSelf();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(persistWhilePlaying);
        releasePlayer();
        unregisterReceiver(unplugged);
        super.onDestroy();
    }
}
