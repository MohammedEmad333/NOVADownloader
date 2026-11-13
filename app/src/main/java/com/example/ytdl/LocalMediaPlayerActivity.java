package com.example.ytdl;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

/** Plays a verified, already-saved library video directly from MediaStore. */
public final class LocalMediaPlayerActivity extends Activity {
    static final String EXTRA_TASK_ID = "nova_player_task_id";
    private static final String POSITION = "video_position";
    private static final String WAS_PLAYING = "video_was_playing";

    private VideoView videoView;
    private DownloadService.Task task;
    private int position;
    private boolean playWhenReady = true;
    private boolean prepared;
    private TextView status;
    private Button external;

    /** Never accept an arbitrary intent URI, a queued task or a remote download URL. */
    static boolean canPlay(DownloadService.Task value) {
        if (value == null || value.state != DownloadService.State.DONE
                || value.outputUri == null || value.mimeType == null
                || !value.mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("video/")) {
            return false;
        }
        try {
            return PendingMediaLedger.isOwnedItemUri(Uri.parse(value.outputUri));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static Intent intentFor(Activity source, DownloadService.Task item) {
        if (!canPlay(item)) throw new IllegalArgumentException("Not a saved local video");
        return new Intent(source, LocalMediaPlayerActivity.class)
                .putExtra(EXTRA_TASK_ID, item.id);
    }

    private String tr(String english, String arabic) {
        return NovaPreferences.text(this, english, arabic);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyChrome(this);
        DownloadService.ensureLoaded(this);
        task = DownloadService.byId(getIntent().getLongExtra(EXTRA_TASK_ID, -1));
        if (!canPlay(task)) {
            Toast.makeText(this, tr("This saved video is unavailable",
                    "هذا الفيديو المحفوظ غير متاح"), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (savedInstanceState != null) {
            position = savedInstanceState.getInt(POSITION, 0);
            playWhenReady = savedInstanceState.getBoolean(WAS_PLAYING, false);
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
        title.setText(task.title);
        title.setMaxLines(2);
        final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = Ui.dp(this, 18);
        root.addView(title, titleParams);

        videoView = new VideoView(this);
        videoView.setBackgroundColor(android.graphics.Color.BLACK);
        final LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        videoParams.topMargin = Ui.dp(this, 15);
        root.addView(videoView, videoParams);
        final MediaController controls = new MediaController(this);
        controls.setAnchorView(videoView);
        videoView.setMediaController(controls);
        videoView.setOnPreparedListener(player -> {
            prepared = true;
            if (position > 0) videoView.seekTo(position);
            if (playWhenReady) videoView.start();
            status.setText(tr("Tap video for playback controls",
                    "اضغط على الفيديو لإظهار أدوات التشغيل"));
        });
        videoView.setOnErrorListener((player, what, extra) -> {
            prepared = false;
            status.setText(tr("NOVA cannot play this video format. Try external player.",
                    "تعذّر تشغيل هذا النوع في نوفا. جرّب مشغلًا خارجيًا."));
            external.setVisibility(View.VISIBLE);
            return true;
        });
        videoView.setOnCompletionListener(player -> {
            playWhenReady = false;
            position = 0;
            status.setText(tr("Playback finished", "انتهى التشغيل"));
        });

        status = Ui.sans(this, 13, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setText(tr("Opening saved video…", "جاري فتح الفيديو المحفوظ…"));
        status.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        external = Ui.button(this, tr("Open with another player", "فتح بمشغل آخر"), false);
        external.setVisibility(View.GONE);
        external.setOnClickListener(v -> openExternal());
        root.addView(external, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));

        setContentView(root);
        videoView.setVideoURI(Uri.parse(task.outputUri));
    }

    private void openExternal() {
        if (!canPlay(task)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(task.outputUri), task.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, tr("No compatible player installed",
                    "لا يوجد مشغل متوافق مثبت"), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onPause() {
        if (videoView != null) {
            playWhenReady = videoView.isPlaying();
            position = videoView.getCurrentPosition();
            videoView.pause();
        }
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (videoView != null && prepared) {
            position = videoView.getCurrentPosition();
            playWhenReady = videoView.isPlaying();
        }
        state.putInt(POSITION, position);
        state.putBoolean(WAS_PLAYING, playWhenReady);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (videoView != null) videoView.stopPlayback();
        super.onDestroy();
    }
}
