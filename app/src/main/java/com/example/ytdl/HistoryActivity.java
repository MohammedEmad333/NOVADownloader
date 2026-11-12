package com.example.ytdl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** NOVA library for completed and interrupted media downloads. */
public final class HistoryActivity extends Activity {
    private final List<DownloadService.Task> items = new ArrayList<>();
    private HistoryAdapter adapter;
    private TextView summary;

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);
        Ui.applyChrome(this);
        DownloadService.ensureLoaded(this);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 18),
                Ui.dp(this, 18), Ui.dp(this, 10));

        final LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);

        final TextView back = Ui.headline(this, 25);
        back.setText("←");
        back.setTextColor(Ui.ACCENT);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Back to Explore and Transfers");
        back.setBackground(Ui.pressable(this, Ui.SURFACE, Ui.ACCENT_SOFT, 15));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(
                Ui.dp(this, 48), Ui.dp(this, 48)));

        final TextView brand = Ui.eyebrow(this, "NOVA / YOUR LIBRARY");
        final LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1f);
        brandParams.leftMargin = Ui.dp(this, 13);
        top.addView(brand, brandParams);
        root.addView(top);

        final LinearLayout heading = Ui.section(this, "LIBRARY / 03",
                "Your collection", "Everything you've saved, with the option to open or share.");
        final LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = Ui.dp(this, 18);
        headingParams.bottomMargin = Ui.dp(this, 18);
        root.addView(heading, headingParams);

        summary = Ui.mono(this, 13, Ui.TEXT);
        summary.setBackground(Ui.hero(this));
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(Ui.dp(this, 17), Ui.dp(this, 15),
                Ui.dp(this, 17), Ui.dp(this, 15));
        summary.setMinHeight(Ui.dp(this, 65));
        final LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.bottomMargin = Ui.dp(this, 14);
        root.addView(summary, summaryParams);

        final ListView list = new ListView(this);
        adapter = new HistoryAdapter();
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 12));
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= items.size()) return;
            final DownloadService.Task task = items.get(position);
            if (task.state == DownloadService.State.FAILED
                    || task.state == DownloadService.State.CANCELLED) {
                DownloadService.retry(this, task);
                Toast.makeText(this, "Added back to Transfers", Toast.LENGTH_SHORT).show();
                finish();
            } else if (task.state == DownloadService.State.DONE && task.outputUri != null) {
                openSaved(task);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private void refresh() {
        items.clear();
        final List<DownloadService.Task> all = DownloadService.snapshot();
        Collections.reverse(all);
        int saved = 0;
        int failed = 0;
        for (final DownloadService.Task task : all) {
            if (task.state.finished()) {
                items.add(task);
                if (task.state == DownloadService.State.DONE) saved++;
                if (task.state == DownloadService.State.FAILED) failed++;
            }
        }
        if (summary != null) {
            summary.setText("✦  " + saved + " saved    ·    " + failed
                    + " need attention\n" + items.size() + " items in your library");
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private TextView action(final String label, final int color, final View.OnClickListener click) {
        final TextView button = Ui.chip(this, label, color);
        button.setMinHeight(Ui.dp(this, 48));
        button.setMinWidth(Ui.dp(this, 48));
        button.setBackground(Ui.pressable(this, Ui.SURFACE_RAISED, Ui.ACCENT_SOFT, 14));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(click);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
        params.rightMargin = Ui.dp(this, 9);
        button.setLayoutParams(params);
        return button;
    }

    private void openSaved(final DownloadService.Task task) {
        try {
            final android.net.Uri uri = android.net.Uri.parse(task.outputUri);
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, task.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (final Exception e) {
            Toast.makeText(this, "No app available to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareSaved(final DownloadService.Task task) {
        try {
            final android.net.Uri uri = android.net.Uri.parse(task.outputUri);
            final Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType(task.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share download"));
        } catch (final Exception e) {
            Toast.makeText(this, "Could not share this file", Toast.LENGTH_SHORT).show();
        }
    }

    private final class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.max(1, items.size()); }
        @Override public Object getItem(final int p) {
            return p < items.size() ? items.get(p) : null;
        }
        @Override public long getItemId(final int p) { return p; }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            if (items.isEmpty()) {
                final LinearLayout empty = Ui.section(HistoryActivity.this, "YOUR SPACE",
                        "A blank canvas", "The things you download will live here.");
                empty.setPadding(Ui.dp(HistoryActivity.this, 18),
                        Ui.dp(HistoryActivity.this, 26), Ui.dp(HistoryActivity.this, 18),
                        Ui.dp(HistoryActivity.this, 26));
                empty.setBackground(Ui.box(HistoryActivity.this, Ui.SURFACE, Ui.LINE, 20));
                return empty;
            }

            final DownloadService.Task task = items.get(position);
            final LinearLayout card = new LinearLayout(HistoryActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.box(HistoryActivity.this, Ui.SURFACE, Ui.LINE, 20));
            card.setPadding(Ui.dp(HistoryActivity.this, 16), Ui.dp(HistoryActivity.this, 15),
                    Ui.dp(HistoryActivity.this, 16), Ui.dp(HistoryActivity.this, 15));

            final LinearLayout heading = new LinearLayout(HistoryActivity.this);
            heading.setOrientation(LinearLayout.HORIZONTAL);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            final TextView title = Ui.headline(HistoryActivity.this, 15);
            title.setText(task.title == null ? "Untitled download" : task.title);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

            final int color = task.state == DownloadService.State.DONE
                    ? Ui.OK : task.state == DownloadService.State.FAILED ? Ui.ERR : Ui.MUTED;
            final TextView state = Ui.chip(HistoryActivity.this, task.state.label, color);
            final LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
            badgeParams.leftMargin = Ui.dp(HistoryActivity.this, 9);
            heading.addView(state, badgeParams);
            card.addView(heading);

            final TextView detail = Ui.sans(HistoryActivity.this, 12, Ui.MUTED);
            detail.setText(task.formatLabel()
                    + (task.state == DownloadService.State.FAILED ? " · Tap to retry" : ""));
            final LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = Ui.dp(HistoryActivity.this, 8);
            card.addView(detail, detailParams);

            if (task.state == DownloadService.State.DONE && task.outputUri != null) {
                final LinearLayout actions = new LinearLayout(HistoryActivity.this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setPadding(0, Ui.dp(HistoryActivity.this, 12), 0, 0);
                actions.addView(action("Open file", Ui.OK, v -> openSaved(task)));
                actions.addView(action("Share", Ui.ACCENT, v -> shareSaved(task)));
                card.addView(actions);
            }
            final LinearLayout wrap = new LinearLayout(HistoryActivity.this);
            wrap.setPadding(0, 0, 0, Ui.dp(HistoryActivity.this, 11));
            wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
            return wrap;
        }
    }
}
