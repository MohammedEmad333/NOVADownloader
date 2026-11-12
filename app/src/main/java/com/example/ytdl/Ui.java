package com.example.ytdl;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * AURORA design system. Native Android, no WebView or external UI dependencies.
 * Surfaces, spacing, typography, states and touch targets are shared by every screen.
 */
public final class Ui {
    public static final int BG = Color.parseColor("#0B1017");
    public static final int SURFACE = Color.parseColor("#141D28");
    public static final int SURFACE_RAISED = Color.parseColor("#1B2835");
    public static final int LINE = Color.parseColor("#2C3B4A");
    public static final int TEXT = Color.parseColor("#F3F7FA");
    public static final int MUTED = Color.parseColor("#9FB0BF");
    public static final int ACCENT = Color.parseColor("#C5F277");
    public static final int ACCENT_SOFT = Color.parseColor("#293C2D");
    public static final int OK = Color.parseColor("#7FE0B3");
    public static final int ERR = Color.parseColor("#FF8C95");
    public static final int WARNING = Color.parseColor("#F8C77B");

    public static final float TYPE_SCALE = 1f;
    public static final int SPACE = 16;
    private Ui() {}

    public static float size(final float baseSp) {
        return baseSp * TYPE_SCALE;
    }

    public static int dp(final Context context, final float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static int sp(final Context context, final float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable box(final Context context, final int fill,
                                       final int stroke, final float radiusDp) {
        final GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(fill);
        background.setCornerRadius(dp(context, Math.max(0, radiusDp)));
        if (stroke != Color.TRANSPARENT) {
            background.setStroke(Math.max(1, dp(context, 1)), stroke);
        }
        return background;
    }

    public static GradientDrawable hero(final Context context) {
        final GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#274536"), Color.parseColor("#1B3035"), SURFACE});
        background.setCornerRadius(dp(context, 26));
        background.setStroke(dp(context, 1), Color.parseColor("#4C6754"));
        return background;
    }

    public static StateListDrawable pressable(final Context context, final int resting,
                                              final int pressed, final float radiusDp) {
        final StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                box(context, pressed, resting == ACCENT ? Color.TRANSPARENT : LINE, radiusDp));
        states.addState(new int[]{android.R.attr.state_focused},
                box(context, SURFACE_RAISED, ACCENT, radiusDp));
        states.addState(new int[]{}, box(context, resting,
                resting == ACCENT ? Color.TRANSPARENT : LINE, radiusDp));
        return states;
    }

    public static TextView mono(final Context context, final float sizeSp, final int color) {
        final TextView text = new TextView(context);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(size(sizeSp));
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        return text;
    }

    public static TextView sans(final Context context, final float sizeSp, final int color) {
        final TextView text = new TextView(context);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextSize(size(sizeSp));
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        return text;
    }

    public static TextView headline(final Context context, final float sizeSp) {
        final TextView text = sans(context, sizeSp, TEXT);
        text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        text.setLetterSpacing(-0.025f);
        return text;
    }

    public static TextView overline(final Context context, final String label) {
        final TextView text = mono(context, 11, ACCENT);
        text.setText(label.toUpperCase(Locale.US));
        text.setLetterSpacing(.16f);
        return text;
    }

    public static Button button(final Context context, final String label, final boolean primary) {
        final Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(size(14));
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        button.setTextColor(primary ? BG : TEXT);
        button.setMinHeight(dp(context, 48));
        button.setMinimumWidth(dp(context, 48));
        button.setPadding(dp(context, 13), 0, dp(context, 13), 0);
        button.setStateListAnimator(null);
        button.setBackground(pressable(context, primary ? ACCENT : SURFACE_RAISED,
                primary ? Color.parseColor("#B0E15E") : ACCENT_SOFT, 15));
        return button;
    }

    public static TextView chip(final Context context, final String label, final int accentColor) {
        final TextView text = mono(context, 11, accentColor);
        text.setText(label);
        text.setGravity(Gravity.CENTER);
        text.setMinHeight(dp(context, 32));
        text.setPadding(dp(context, 11), dp(context, 5),
                dp(context, 11), dp(context, 5));
        text.setBackground(box(context, SURFACE_RAISED, LINE, 12));
        return text;
    }

    public static LinearLayout section(final Context context, final String eyebrow,
                                       final String title, final String subtitle) {
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        final TextView upper = overline(context, eyebrow);
        root.addView(upper);
        final TextView heading = headline(context, 22);
        heading.setText(title);
        final LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                -1, -2);
        hp.topMargin = dp(context, 5);
        root.addView(heading, hp);
        if (subtitle != null && !subtitle.isEmpty()) {
            final TextView detail = sans(context, 13, MUTED);
            detail.setText(subtitle);
            detail.setLineSpacing(dp(context, 2), 1f);
            final LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = dp(context, 6);
            root.addView(detail, sp);
        }
        return root;
    }

    public static void applyChrome(final Activity activity) {
        if (activity == null) return;
        final Window window = activity.getWindow();
        if (window == null) return;
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // White system icons on the dark canvas.
            window.getDecorView().setSystemUiVisibility(0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.setNavigationBarDividerColor(BG);
        }
    }

    public static String bytes(final long count) {
        if (count < 1024) return count + " B";
        if (count < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", count / 1024.0);
        }
        if (count < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", count / (1024.0 * 1024));
        }
        return String.format(Locale.US, "%.2f GB", count / (1024.0 * 1024 * 1024));
    }
}
