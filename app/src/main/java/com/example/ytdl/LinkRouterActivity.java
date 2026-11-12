package com.example.ytdl;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** AURORA handoff for Android shares and supported open-link intents. */
public final class LinkRouterActivity extends Activity {
    @Override protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyChrome(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 24),
                Ui.dp(this, 24), Ui.dp(this, 24));
        final TextView mark = Ui.headline(this, 40);
        mark.setText("↓");
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(Ui.BG);
        mark.setBackground(Ui.box(this, Ui.ACCENT, android.graphics.Color.TRANSPARENT, 22));
        root.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84)));
        final TextView heading = Ui.headline(this, 26);
        heading.setText("Aurora Downloader");
        heading.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = Ui.dp(this, 22);
        root.addView(heading, hp);
        final TextView status = Ui.sans(this, 14, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setText("Opening your media…");
        final LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Ui.dp(this, 7);
        root.addView(status, sp);
        setContentView(root);
        final Intent next = new Intent(this, MainActivity.class);
        final String link = extractLink(getIntent());
        if (link != null && !link.isEmpty()) {
            next.setAction(Intent.ACTION_SEND);
            next.setType("text/plain");
            next.putExtra(Intent.EXTRA_TEXT, link);
        }
        startActivity(next);
        finish();
    }

    private static String extractLink(final Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return LinkParser.firstSupportedMediaLink(intent.getData().toString());
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            return LinkParser.firstSupportedMediaLink(intent.getStringExtra(Intent.EXTRA_TEXT));
        }
        return null;
    }
}
