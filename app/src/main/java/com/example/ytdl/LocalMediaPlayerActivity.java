package com.example.ytdl;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.util.List;
import java.util.Locale;

/**
 * Offline playback UI. Video stays in the Activity; audio is owned by the
 * user-started media foreground service and survives closing this screen.
 */
public final class LocalMediaPlayerActivity extends Activity {
    static final String EXTRA_TASK_ID = "nova_player_task_id";
    static final String STATE_POSITION = "video_position";
    static final String STATE_WAS_PLAYING = "video_was_playing";
    private static final String PROGRESS_PREFS = "nova_offline_playback_positions";
    private static final String PROGRESS_PREFIX = "media-";
    private static final int MIN_RESUME_MS = 5_000;
    private static final int END_MARGIN_MS = 5_000;
    private static final int SKIP_MS = 10_000;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private VideoView videoView;
    private MediaPlayer videoPlayer;
    private AudioPlaybackService audioService;
    private SeekBar audioSeek;
    private SeekBar videoSeek;
    private TextView audioTime, videoTime;
    private Button audioToggle, skipBack, skipForward, speedButton, pipButton;
    private View videoControls;
    private DownloadService.Task task;
    private TextView status;
    private Button external;
    private boolean prepared, foreground, audioBound, playWhenReady = true;
    private int position, speedIndex = PlaybackSpeed.DEFAULT;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!(binder instanceof AudioPlaybackService.LocalBinder)) return;
            audioService = ((AudioPlaybackService.LocalBinder) binder).service();
            audioBound = true;
            updateAudioUi();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            audioService = null;
            audioBound = false;
            prepared = false;
            if (audioToggle != null) audioToggle.setEnabled(false);
        }
    };

    private final Runnable pollVideo = new Runnable() {
        @Override public void run() {
            if (videoView == null || !prepared) return;
            try {
                final int duration = Math.max(0, videoView.getDuration());
                final int current = Math.max(0, videoView.getCurrentPosition());
                if (videoSeek != null && !videoSeeking) {
                    videoSeek.setMax(Math.max(1, duration));
                    videoSeek.setEnabled(duration > 0);
                    videoSeek.setProgress(Math.min(current, duration));
                }
                if (videoTime != null && !videoSeeking) {
                    videoTime.setText(formatTime(current) + " / " + formatTime(duration));
                }
            } catch (IllegalStateException unavailable) {
                return;
            }
            ui.postDelayed(this, 500);
        }
    };
    private boolean videoSeeking;

    private final Runnable pollAudio = new Runnable() {
        @Override public void run() {
            if (audioService == null) return;
            updateAudioUi();
            ui.postDelayed(this, 500);
        }
    };

    static boolean canPlay(DownloadService.Task value) {
        if (value == null || value.state != DownloadService.State.DONE
                || value.outputUri == null || value.mimeType == null) return false;
        final String mime = value.mimeType.toLowerCase(Locale.ROOT);
        final boolean video = mime.startsWith("video/");
        final boolean audio = mime.startsWith("audio/");
        if (!video && !audio) return false;
        try {
            final Uri uri = Uri.parse(value.outputUri);
            if (!PendingMediaLedger.isOwnedItemUri(uri)) return false;
            final List<String> segments = uri.getPathSegments();
            if ("video".equals(segments.get(1))) return video;
            if ("audio".equals(segments.get(1))) return audio;
            return "downloads".equals(segments.get(1));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static Intent intentFor(Activity source, DownloadService.Task item) {
        if (!canPlay(item)) throw new IllegalArgumentException("Not a saved local media file");
        return new Intent(source, LocalMediaPlayerActivity.class)
                .putExtra(EXTRA_TASK_ID, item.id);
    }

    static String formatTime(long milliseconds) {
        final long seconds = Math.max(0, milliseconds) / 1000L;
        final long hours = seconds / 3600L;
        final long minutes = seconds / 60L % 60L;
        final long remaining = seconds % 60L;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remaining)
                : String.format(Locale.US, "%d:%02d", minutes, remaining);
    }

    static int seekTarget(int desiredMs, int durationMs) {
        return Math.max(0, Math.min(Math.max(0, durationMs), desiredMs));
    }

    static void writePlaybackState(Bundle saved, int currentPosition, boolean currentlyPlaying) {
        saved.putInt(STATE_POSITION, Math.max(0, currentPosition));
        saved.putBoolean(STATE_WAS_PLAYING, currentlyPlaying);
    }

    static int skipTarget(int currentMs, int durationMs, int deltaMs) {
        final long target = (long) Math.max(0, currentMs) + deltaMs;
        return (int) Math.max(0L, Math.min(Math.max(0, durationMs), target));
    }

    static int savedPosition(Context context, DownloadService.Task item) {
        if (!canPlay(item)) return 0;
        final SharedPreferences prefs = context.getSharedPreferences(PROGRESS_PREFS, MODE_PRIVATE);
        final String key = PROGRESS_PREFIX + item.id;
        if (!item.outputUri.equals(prefs.getString(key + "-uri", null))) return 0;
        return Math.max(0, prefs.getInt(key + "-ms", 0));
    }

    static void rememberPosition(Context context, DownloadService.Task item,
                                 int currentMs, int durationMs) {
        if (!canPlay(item)) return;
        final String key = PROGRESS_PREFIX + item.id;
        final SharedPreferences.Editor editor = context.getSharedPreferences(
                PROGRESS_PREFS, MODE_PRIVATE).edit();
        if (currentMs < MIN_RESUME_MS || (durationMs > 0
                && (long) currentMs >= (long) durationMs - END_MARGIN_MS)) {
            editor.remove(key + "-uri").remove(key + "-ms").apply();
        } else {
            editor.putString(key + "-uri", item.outputUri)
                    .putInt(key + "-ms", Math.max(0, currentMs)).apply();
        }
    }

    private String tr(String en, String ar) {
        return NovaPreferences.text(this, en, ar);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyChrome(this);
        DownloadService.ensureLoaded(this);
        task = getIntent() == null ? null :
                DownloadService.byId(getIntent().getLongExtra(EXTRA_TASK_ID, -1));
        if (!canPlay(task)) {
            Toast.makeText(this, tr("This saved media file is unavailable",
                    "ملف الوسائط المحفوظ غير متاح"), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        speedIndex = PlaybackSpeed.load(this);
        if (savedInstanceState != null) {
            position = Math.max(0, savedInstanceState.getInt(STATE_POSITION, 0));
            playWhenReady = savedInstanceState.getBoolean(STATE_WAS_PLAYING, false);
        } else {
            position = savedPosition(this, task);
        }

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(NovaPreferences.arabic(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 18),
                Ui.dp(this, 16), Ui.dp(this, 18));
        final Button back = Ui.button(this, tr("← Back to Library", "← العودة للمكتبة"), false);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));

        final TextView title = Ui.headline(this, 19);
        title.setText(task.title == null ? tr("Saved media", "وسائط محفوظة") : task.title);
        title.setMaxLines(2);
        final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = Ui.dp(this, 18);
        root.addView(title, titleParams);
        status = Ui.sans(this, 13, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setText(tr("Opening saved media…", "جاري فتح الملف المحفوظ…"));
        status.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));

        external = Ui.button(this, tr("Open with another player", "فتح بمشغل آخر"), false);
        external.setVisibility(View.GONE);
        external.setOnClickListener(v -> openExternal());
        final boolean audio = isAudio();
        if (audio) buildAudio(root);
        else buildVideo(root);
        speedButton = Ui.button(this, tr("Speed ", "السرعة ") + PlaybackSpeed.label(speedIndex), false);
        speedButton.setOnClickListener(v -> changeSpeed());
        root.addView(speedButton, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        root.addView(status);
        root.addView(external, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        setContentView(root);
        if (audio) connectAudio();
        else videoView.setVideoURI(Uri.parse(task.outputUri));
    }

    private boolean isAudio() {
        return task != null && task.mimeType.toLowerCase(Locale.ROOT).startsWith("audio/");
    }

    private void buildVideo(LinearLayout root) {
        videoView = new VideoView(this);
        videoView.setBackgroundColor(android.graphics.Color.BLACK);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0, 1f);
        params.topMargin = Ui.dp(this, 15);
        root.addView(videoView, params);
        final MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);

        final LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        videoControls = tools;
        videoTime = Ui.mono(this, 13, Ui.MUTED);
        videoTime.setText("0:00 / 0:00");
        videoTime.setGravity(Gravity.CENTER);
        tools.addView(videoTime);
        videoSeek = new SeekBar(this);
        videoSeek.setEnabled(false);
        videoSeek.setContentDescription(tr("Seek video position", "تغيير موضع الفيديو"));
        videoSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && videoTime != null && videoView != null) {
                    videoTime.setText(formatTime(value) + " / "
                            + formatTime(Math.max(0, videoView.getDuration())));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                videoSeeking = true;
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (prepared && videoView != null) {
                    final int duration = Math.max(0, videoView.getDuration());
                    if (duration > 0) videoView.seekTo(seekTarget(bar.getProgress(), duration));
                }
                videoSeeking = false;
            }
        });
        tools.addView(videoSeek);
        addSkipControls(tools);
        pipButton = Ui.button(this, tr("Mini player (PiP)", "نافذة فيديو عائمة"), false);
        pipButton.setEnabled(false);
        pipButton.setOnClickListener(v -> enterPip());
        tools.addView(pipButton, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        root.addView(tools);

        videoView.setOnPreparedListener(player -> {
            videoPlayer = player;
            prepared = true;
            setSkipEnabled(true);
            ui.removeCallbacks(pollVideo);
            ui.post(pollVideo);
            if (pipButton != null) pipButton.setEnabled(true);
            if (position > 0) videoView.seekTo(position);
            if (playWhenReady && (foreground || isInPictureInPictureMode())) videoView.start();
            applyVideoSpeed();
            status.setText(tr("Tap video for playback controls",
                    "اضغط على الفيديو لإظهار أدوات التشغيل"));
        });
        videoView.setOnErrorListener((player, what, extra) -> {
            showPlaybackError();
            return true;
        });
        videoView.setOnCompletionListener(player -> {
            playWhenReady = false;
            position = 0;
            rememberPosition(this, task, 0, 0);
            ui.removeCallbacks(pollVideo);
            if (videoSeek != null) videoSeek.setProgress(0);
            if (videoTime != null) videoTime.setText("0:00 / "
                    + formatTime(Math.max(0, videoView.getDuration())));
            status.setText(tr("Playback finished", "انتهى التشغيل"));
        });
    }

    private void buildAudio(LinearLayout root) {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackground(Ui.box(this, Ui.SURFACE, Ui.LINE, 20));
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 24),
                Ui.dp(this, 18), Ui.dp(this, 24));
        final LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        panelParams.topMargin = Ui.dp(this, 15);
        root.addView(panel, panelParams);

        final TextView symbol = Ui.headline(this, 38);
        symbol.setText("♫");
        symbol.setGravity(Gravity.CENTER);
        panel.addView(symbol, new LinearLayout.LayoutParams(-1, Ui.dp(this, 96)));

        audioTime = Ui.mono(this, 14, Ui.MUTED);
        audioTime.setText("0:00 / 0:00");
        audioTime.setGravity(Gravity.CENTER);
        panel.addView(audioTime, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));

        audioSeek = new SeekBar(this);
        audioSeek.setMax(1);
        audioSeek.setEnabled(false);
        audioSeek.setContentDescription(tr("Seek audio position", "تغيير موضع التشغيل"));
        audioSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && audioService != null) {
                    showAudioTime(value, audioService.snapshot().duration);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (audioService != null) audioService.seekTo(bar.getProgress());
            }
        });
        panel.addView(audioSeek, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        addSkipControls(panel);
        audioToggle = Ui.button(this, tr("Pause", "إيقاف مؤقت"), true);
        audioToggle.setEnabled(false);
        audioToggle.setOnClickListener(v -> {
            if (audioService != null) {
                audioService.setPlaying(!audioService.snapshot().playing);
                updateAudioUi();
            }
        });
        panel.addView(audioToggle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
    }

    private void connectAudio() {
        try {
            // Starting a media foreground service is permitted from the visible player.
            startForegroundService(AudioPlaybackService.startIntent(
                    this, task.id, position, playWhenReady));
            bindService(new Intent(this, AudioPlaybackService.class),
                    connection, BIND_AUTO_CREATE);
            ui.post(pollAudio);
        } catch (RuntimeException unavailable) {
            showPlaybackError();
        }
    }

    private void updateAudioUi() {
        if (audioService == null || task == null) return;
        final AudioPlaybackService.Snapshot snap = audioService.snapshot();
        if (snap.taskId != task.id) return;
        prepared = snap.prepared;
        position = snap.position;
        speedIndex = snap.speedIndex;
        if (audioSeek != null) {
            audioSeek.setMax(Math.max(1, snap.duration));
            audioSeek.setEnabled(prepared && snap.duration > 0);
            audioSeek.setProgress(position);
        }
        setSkipEnabled(prepared && snap.duration > 0);
        if (audioToggle != null) {
            audioToggle.setEnabled(prepared);
            audioToggle.setText(snap.playing ? tr("Pause", "إيقاف مؤقت") : tr("Play", "تشغيل"));
        }
        if (speedButton != null) speedButton.setText(
                tr("Speed ", "السرعة ") + PlaybackSpeed.label(speedIndex));
        showAudioTime(position, snap.duration);
        if (status != null) status.setText(snap.error == null
                ? tr("Saved audio · background playback enabled",
                        "صوت محفوظ · التشغيل في الخلفية متاح")
                : tr("Audio playback unavailable", "تعذّر تشغيل الصوت"));
    }

    private void addSkipControls(LinearLayout parent) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        skipBack = Ui.button(this, tr("−10 seconds", "رجوع ١٠ ثوانٍ"), false);
        skipForward = Ui.button(this, tr("+10 seconds", "تقديم ١٠ ثوانٍ"), false);
        skipBack.setContentDescription(tr("Rewind 10 seconds", "الرجوع عشر ثوانٍ"));
        skipForward.setContentDescription(tr("Skip forward 10 seconds", "التقديم عشر ثوانٍ"));
        skipBack.setEnabled(false);
        skipForward.setEnabled(false);
        skipBack.setOnClickListener(v -> skip(-SKIP_MS));
        skipForward.setOnClickListener(v -> skip(SKIP_MS));
        row.addView(skipBack, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        row.addView(skipForward, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        parent.addView(row);
    }

    private void setSkipEnabled(boolean enabled) {
        if (skipBack != null) skipBack.setEnabled(enabled);
        if (skipForward != null) skipForward.setEnabled(enabled);
    }

    private void skip(int deltaMs) {
        if (!prepared) return;
        if (audioService != null) {
            final AudioPlaybackService.Snapshot snap = audioService.snapshot();
            if (snap.duration <= 0) return;
            position = skipTarget(snap.position, snap.duration, deltaMs);
            audioService.seekTo(position);
            updateAudioUi();
        } else if (videoView != null) {
            final int duration = videoView.getDuration();
            if (duration <= 0) return;
            position = skipTarget(videoView.getCurrentPosition(), duration, deltaMs);
            videoView.seekTo(position);
        }
    }

    private void changeSpeed() {
        speedIndex = PlaybackSpeed.next(speedIndex);
        PlaybackSpeed.save(this, speedIndex);
        if (audioService != null) audioService.setSpeedIndex(speedIndex);
        if (videoPlayer != null && prepared) applyVideoSpeed();
        if (speedButton != null) speedButton.setText(
                tr("Speed ", "السرعة ") + PlaybackSpeed.label(speedIndex));
    }

    private void applyVideoSpeed() {
        if (videoPlayer == null || !prepared) return;
        try {
            videoPlayer.setPlaybackParams(videoPlayer.getPlaybackParams()
                    .setSpeed(PlaybackSpeed.value(speedIndex)));
        } catch (RuntimeException unsupported) {
            status.setText(tr("Speed adjustment is unsupported for this video",
                    "تعديل السرعة غير متاح لهذا الفيديو"));
        }
    }

    private void enterPip() {
        if (videoView == null || !PlaybackSpeed.canEnterPip(true, prepared,
                videoView.isPlaying()) || isInPictureInPictureMode()) return;
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9)).build());
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, tr("Mini player unavailable on this device",
                    "النافذة العائمة غير متاحة على هذا الجهاز"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean inPip,
                                                         Configuration configuration) {
        super.onPictureInPictureModeChanged(inPip, configuration);
        if (videoControls != null) videoControls.setVisibility(inPip ? View.GONE : View.VISIBLE);
        if (speedButton != null) speedButton.setVisibility(inPip ? View.GONE : View.VISIBLE);
        if (!inPip && !foreground && videoView != null && videoView.isPlaying()) {
            position = videoView.getCurrentPosition();
            videoView.pause();
        }
    }

    private void showAudioTime(int current, int duration) {
        if (audioTime != null) audioTime.setText(
                formatTime(current) + " / " + formatTime(duration));
    }

    private void showPlaybackError() {
        prepared = false;
        ui.removeCallbacks(pollAudio);
        if (audioToggle != null) audioToggle.setEnabled(false);
        if (audioSeek != null) audioSeek.setEnabled(false);
        if (videoSeek != null) videoSeek.setEnabled(false);
        ui.removeCallbacks(pollVideo);
        setSkipEnabled(false);
        if (status != null) status.setText(tr(
                "NOVA cannot play this format. Try an external player.",
                "تعذّر تشغيل هذا النوع في نوفا. جرّب مشغلًا خارجيًا."));
        if (external != null) external.setVisibility(View.VISIBLE);
    }

    private void openExternal() {
        if (!canPlay(task)) return;
        try {
            final Intent intent = LibraryFileActions.viewIntent(task);
            if (intent != null) startActivity(intent);
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, tr("No compatible player installed",
                    "لا يوجد مشغل متوافق مثبت"), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (videoView != null && prepared) {
            ui.removeCallbacks(pollVideo);
            ui.post(pollVideo);
            if (playWhenReady && !videoView.isPlaying()) videoView.start();
        }
        if (audioService != null) {
            ui.removeCallbacks(pollAudio);
            ui.post(pollAudio);
        }
    }

    @Override protected void onPause() {
        foreground = false;
        ui.removeCallbacks(pollAudio);
        ui.removeCallbacks(pollVideo);
        if (videoView != null && prepared) {
            playWhenReady = videoView.isPlaying();
            position = videoView.getCurrentPosition();
            rememberPosition(this, task, position, videoView.getDuration());
            if (!isInPictureInPictureMode()) videoView.pause();
        }
        // Audio playback is intentionally independent of Activity foreground state.
        if (audioService != null) position = audioService.snapshot().position;
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle saved) {
        if (videoView != null && prepared) {
            position = videoView.getCurrentPosition();
            // Capture false too: a paused video must stay paused after recreation.
            playWhenReady = videoView.isPlaying();
        } else if (audioService != null) {
            final AudioPlaybackService.Snapshot snap = audioService.snapshot();
            position = snap.position;
            playWhenReady = snap.playing;
        }
        writePlaybackState(saved, position, playWhenReady);
        super.onSaveInstanceState(saved);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(pollAudio);
        ui.removeCallbacks(pollVideo);
        if (videoView != null) videoView.stopPlayback();
        videoPlayer = null;
        if (audioBound) unbindService(connection);
        audioBound = false;
        audioService = null;
        super.onDestroy();
    }
}
