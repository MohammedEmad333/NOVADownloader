package com.example.ytdl;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
public final class NovaDesignSystemTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Test public void novaPaletteUsesDistinctReadableSurfaces() {
        assertEquals(Color.parseColor("#091022"), Ui.BG);
        assertEquals(Color.parseColor("#8CE0FF"), Ui.ACCENT);
        assertEquals(Color.parseColor("#F4A7FF"), Ui.SECONDARY);
        assertNotEquals(Ui.BG, Ui.TEXT);
        assertNotEquals(Ui.SURFACE, Ui.SURFACE_RAISED);
        assertNotEquals(Ui.ACCENT, Ui.ERR);
    }

    @Test public void buttonsHaveAccessibleTouchTargets() {
        final Button primary = Ui.button(context, "Discover media", true);
        final Button secondary = Ui.button(context, "Filters", false);
        assertEquals("Discover media", primary.getText().toString());
        assertTrue(primary.getMinHeight() >= Ui.dp(context, 48));
        assertTrue(secondary.getMinHeight() >= Ui.dp(context, 48));
        assertEquals(Ui.BG, primary.getCurrentTextColor());
        assertEquals(Ui.TEXT, secondary.getCurrentTextColor());
    }

    @Test public void heroAndSectionsHaveSemanticallyDistinctHierarchy() {
        final GradientDrawable hero = Ui.hero(context);
        assertNotNull(hero.getColors());
        assertEquals(3, hero.getColors().length);
        assertNotNull(Ui.glow(context));
        final LinearLayout section = Ui.section(context, "EXPLORE / 01",
                "Find it. Save it.", "Discover and queue media");
        assertEquals(LinearLayout.VERTICAL, section.getOrientation());
        assertEquals(3, section.getChildCount());
        assertEquals("Find it. Save it.",
                ((TextView) section.getChildAt(1)).getText().toString());
        assertEquals("YOUR MEDIA UNIVERSE",
                Ui.eyebrow(context, "Your media universe").getText().toString());
    }

    @Test public void sourceChipsHaveReadableLabels() {
        final TextView chip = Ui.chip(context, "YouTube", Ui.ACCENT);
        assertEquals("YouTube", chip.getText().toString());
        assertTrue(chip.getMinHeight() >= Ui.dp(context, 32));
        assertNotNull(chip.getBackground());
    }
}
