package com.example.ytdl;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class AuroraDesignSystemTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Test public void paletteHasReadableTextAndDistinctAccents() {
        assertEquals(Color.parseColor("#0B1017"), Ui.BG);
        assertEquals(Color.parseColor("#C5F277"), Ui.ACCENT);
        assertNotEquals(Ui.BG, Ui.TEXT);
        assertNotEquals(Ui.SURFACE, Ui.SURFACE_RAISED);
    }

    @Test public void controlsMeetMinimumTouchTargets() {
        final Button primary = Ui.button(context, "Find media", true);
        final Button secondary = Ui.button(context, "Filters", false);
        assertEquals("Find media", primary.getText().toString());
        assertTrue(primary.getMinHeight() >= Ui.dp(context, 48));
        assertTrue(secondary.getMinHeight() >= Ui.dp(context, 48));
        assertEquals(Ui.BG, primary.getCurrentTextColor());
        assertEquals(Ui.TEXT, secondary.getCurrentTextColor());
    }

    @Test public void heroHasDarkGradientAndSectionHasSemanticHierarchy() {
        final GradientDrawable hero = Ui.hero(context);
        assertNotNull(hero.getColors());
        assertEquals(3, hero.getColors().length);
        final LinearLayout section = Ui.section(context, "DISCOVER / 01",
                "Find your next download", "Paste a link or search");
        assertEquals(LinearLayout.VERTICAL, section.getOrientation());
        assertEquals(3, section.getChildCount());
        assertEquals("Find your next download",
                ((TextView) section.getChildAt(1)).getText().toString());
    }

    @Test public void badgesAndButtonsHaveVisibleLabels() {
        final TextView badge = Ui.chip(context, "Downloading", Ui.ACCENT);
        assertEquals("Downloading", badge.getText().toString());
        assertTrue(badge.getMinHeight() >= Ui.dp(context, 32));
        assertNotNull(badge.getBackground());
        final View surface = Ui.button(context, "Open file", false);
        assertNotNull(surface.getBackground());
    }
}
