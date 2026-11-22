package com.example.ytdl;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared three-destination navigation for Explore, Transfers and Library. */
final class NovaNavigation {
    static final int EXPLORE = 0;
    static final int TRANSFERS = 1;
    static final int LIBRARY = 2;
    static final String EXTRA_DESTINATION = "nova_navigation_destination";

    private NovaNavigation() {}

    static Intent mainIntent(Context context, int destination) {
        if (destination != EXPLORE && destination != TRANSFERS) {
            throw new IllegalArgumentException("Invalid Main destination");
        }
        return new Intent(context, MainActivity.class)
                .putExtra(EXTRA_DESTINATION, destination)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static int requestedDestination(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_DESTINATION)) return -1;
        final int destination = intent.getIntExtra(EXTRA_DESTINATION, -1);
        return destination == EXPLORE || destination == TRANSFERS ? destination : -1;
    }

    static TextView tab(Context context, String label, boolean active,
                        View.OnClickListener clicked) {
        final TextView view = Ui.mono(context, 13, Ui.MUTED);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(Ui.dp(context, 48));
        view.setPadding(Ui.dp(context, 5), Ui.dp(context, 10),
                Ui.dp(context, 5), Ui.dp(context, 10));
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription(label.trim());
        view.setOnClickListener(clicked);
        style(context, view, active);
        return view;
    }

    static void style(Context context, TextView tab, boolean active) {
        tab.setTextColor(active ? Ui.BG : Ui.MUTED);
        tab.setBackground(Ui.pressable(context, active
                ? Ui.ACCENT : Ui.SURFACE_RAISED, Ui.ACCENT_SOFT, 17));
        tab.setSelected(active);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tab.setStateDescription(NovaPreferences.text(context,
                    active ? "Selected" : "Not selected",
                    active ? "محدد" : "غير محدد"));
        }
    }

    static LinearLayout bar(Context context, TextView explore, TextView transfers,
                            TextView library) {
        final LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setLayoutDirection(NovaPreferences.arabic(context)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(Ui.dp(context, 15), Ui.dp(context, 11),
                Ui.dp(context, 15), Ui.dp(context, 12));
        wrap.setBackground(Ui.box(context, Ui.SURFACE, Ui.LINE, 0));
        for (TextView tab : new TextView[]{explore, transfers, library}) {
            tab.setMinHeight(Ui.dp(context, 52));
        }
        wrap.addView(explore, new LinearLayout.LayoutParams(0, -2, 1f));
        final LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1f);
        second.setMarginStart(Ui.dp(context, 7));
        wrap.addView(transfers, second);
        final LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(0, -2, 1f);
        third.setMarginStart(Ui.dp(context, 7));
        wrap.addView(library, third);
        return wrap;
    }
}
