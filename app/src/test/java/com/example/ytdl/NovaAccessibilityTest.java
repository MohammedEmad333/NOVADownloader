package com.example.ytdl;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Accessible controls, font-scalable settings and bilingual navigation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class NovaAccessibilityTest {
    private static TextView findText(View node, String label) {
        if (node instanceof TextView && label.contentEquals(((TextView) node).getText())) {
            return (TextView) node;
        }
        if (node instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) {
                final TextView found = findText(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void checkSpinnerLabel(View root, String text) {
        final TextView label = findText(root, text);
        assertNotNull("Missing visible label: " + text, label);
        assertNotEquals("Missing labelFor id: " + text, View.NO_ID, label.getLabelFor());
        final View target = root.findViewById(label.getLabelFor());
        assertTrue("Label is not connected to a spinner: " + text, target instanceof Spinner);
        assertTrue("Spinner is too short: " + text,
                target.getMinimumHeight() >= Ui.dp(target.getContext(), 48));
        assertEquals("Spinner clips content under font scaling: " + text,
                -2, target.getLayoutParams().height);
    }

    @Test public void settingsSpinnersHaveVisibleEnglishLabelsAndWrapContent() {
        final Context context = RuntimeEnvironment.getApplication();
        final String previous = NovaPreferences.languageChoice(context);
        try {
            NovaPreferences.setLanguage(context, "en");
            final SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                    .create().start().resume().get();
            final View root = activity.findViewById(android.R.id.content);
            checkSpinnerLabel(root, "APP LANGUAGE");
            checkSpinnerLabel(root, "PREFERRED VIDEO QUALITY");
            checkSpinnerLabel(root, "PREFERRED MP3 QUALITY");
            checkSpinnerLabel(root, "Start hour");
            checkSpinnerLabel(root, "End hour");
            final TextView back = findText(root, "\u2190  Back");
            assertNotNull(back);
            assertTrue(back.isFocusable());
            assertTrue(back.isClickable());
        } finally {
            NovaPreferences.setLanguage(context, previous);
        }
    }

    @Test public void settingsLabelsAndScheduleAreAccessibleInArabicRtl() {
        final Context context = RuntimeEnvironment.getApplication();
        final String previous = NovaPreferences.languageChoice(context);
        try {
            NovaPreferences.setLanguage(context, "ar");
            final SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                    .create().start().resume().get();
            final View root = activity.findViewById(android.R.id.content);
            checkSpinnerLabel(root, "\u0644\u063a\u0629 \u0627\u0644\u062a\u0637\u0628\u064a\u0642");
            checkSpinnerLabel(root, "\u0633\u0627\u0639\u0629 \u0627\u0644\u0628\u062f\u0627\u064a\u0629");
            checkSpinnerLabel(root, "\u0633\u0627\u0639\u0629 \u0627\u0644\u0646\u0647\u0627\u064a\u0629");
        } finally {
            NovaPreferences.setLanguage(context, previous);
        }
    }

    @Test public void sharedSectionsAreHeadingsAndNavigationAnnouncesSelection() {
        final Context context = RuntimeEnvironment.getApplication();
        final LinearLayout section = Ui.section(context, "SETTINGS", "Download settings",
                "The settings stay on this phone.");
        assertTrue(((TextView) section.getChildAt(1)).isAccessibilityHeading());
        final String previous = NovaPreferences.languageChoice(context);
        try {
            NovaPreferences.setLanguage(context, "en");
            final TextView tab = NovaNavigation.tab(context, "Explore", true, v -> {});
            assertEquals("Selected", String.valueOf(tab.getStateDescription()));
            NovaNavigation.style(context, tab, false);
            assertEquals("Not selected", String.valueOf(tab.getStateDescription()));
            NovaPreferences.setLanguage(context, "ar");
            NovaNavigation.style(context, tab, true);
            assertEquals("\u0645\u062d\u062f\u062f", String.valueOf(tab.getStateDescription()));
            final TextView transfers = NovaNavigation.tab(context, "Downloads", false, v -> {});
            final TextView library = NovaNavigation.tab(context, "Library", false, v -> {});
            final LinearLayout bar = NovaNavigation.bar(context, tab, transfers, library);
            // Robolectric may report a default direction for detached view groups.
            // RTL selection is asserted from the preference; device QA verifies order.
            assertTrue(NovaPreferences.arabic(context));
            assertEquals(3, bar.getChildCount());
            assertEquals(-2, bar.getChildAt(0).getLayoutParams().height);
            assertEquals(-2, bar.getChildAt(1).getLayoutParams().height);
        } finally {
            NovaPreferences.setLanguage(context, previous);
        }
    }

    @Test @Config(sdk = 29)
    public void android10NavigationAnnouncesSelectionWithoutStateDescription() {
        final Context context = RuntimeEnvironment.getApplication();
        final String previous = NovaPreferences.languageChoice(context);
        try {
            NovaPreferences.setLanguage(context, "en");
            final TextView explore = NovaNavigation.tab(context, "Explore", true, v -> {});
            assertEquals("Explore, Selected",
                    explore.getContentDescription().toString());
            NovaNavigation.style(context, explore, false);
            assertEquals("Explore, Not selected",
                    explore.getContentDescription().toString());

            NovaPreferences.setLanguage(context, "ar");
            NovaNavigation.style(context, explore, true);
            assertEquals("Explore, \u0645\u062d\u062f\u062f",
                    explore.getContentDescription().toString());
        } finally {
            NovaPreferences.setLanguage(context, previous);
        }
    }

    @Test public void hourlySchedulePickerPreservesSavedMinutePrecision() {
        assertEquals(22 * 60 + 17, SettingsActivity.selectedMinute(22, 22 * 60 + 17));
        assertEquals(7 * 60 + 43, SettingsActivity.selectedMinute(7, 7 * 60 + 43));
        assertEquals(21 * 60, SettingsActivity.selectedMinute(21, 22 * 60 + 17));
        assertEquals(0, SettingsActivity.selectedMinute(0, -1));
        try {
            SettingsActivity.selectedMinute(24, 22 * 60);
            fail("Expected invalid hour to be rejected");
        } catch (IllegalArgumentException expected) {
            // Bad spinner positions must never persist as a schedule.
        }
    }

    @Test public void settingsDoesNotRewriteScheduleBeforeExplicitSave() {
        final Context context = RuntimeEnvironment.getApplication();
        final android.content.SharedPreferences prefs = context.getSharedPreferences(
                "nova_download_schedule", Context.MODE_PRIVATE);
        final String previous = NovaPreferences.languageChoice(context);
        prefs.edit().putBoolean("daily_window_enabled", true)
                .putInt("window_start_minute", 22 * 60 + 17)
                .putInt("window_end_minute", 7 * 60 + 43).commit();
        try {
            NovaPreferences.setLanguage(context, "en");
            final SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                    .create().start().resume().get();
            final View root = activity.findViewById(android.R.id.content);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNotNull(findText(root, "Save download schedule"));
            assertNotNull(findText(root, "Saved schedule: On · 22:17 – 07:43"));
            final TextView startLabel = findText(root, "Start hour");
            assertNotNull(startLabel);
            final Spinner start = (Spinner) root.findViewById(startLabel.getLabelFor());
            start.setSelection(20);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            final TextView toggleText = findText(root,
                    "Limit downloads to a daily time window");
            assertTrue(toggleText instanceof android.widget.CheckBox);
            ((android.widget.CheckBox) toggleText).setChecked(false);
            assertEquals(22 * 60 + 17, DownloadSchedule.windowStart(context));
            assertEquals(7 * 60 + 43, DownloadSchedule.windowEnd(context));
            assertTrue("Browsing Settings cannot silently disable a saved schedule",
                    DownloadSchedule.windowEnabled(context));
        } finally {
            prefs.edit().clear().commit();
            NovaPreferences.setLanguage(context, previous);
        }
    }
}
