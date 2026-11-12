package com.example.ytdl;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class NovaScreenSmokeTest {
    private static boolean hasText(final View view, final String expected) {
        if (view instanceof TextView
                && expected.contentEquals(((TextView) view).getText())) return true;
        if (!(view instanceof ViewGroup)) return false;
        final ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (hasText(group.getChildAt(i), expected)) return true;
        }
        return false;
    }

    @Test public void mainScreenShowsThreeDestinationsAndDiscovery() {
        final MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().get();
        final View root = activity.findViewById(android.R.id.content);
        assertNotNull(root);
        assertTrue(hasText(root, "NOVA"));
        assertTrue(hasText(root, "✦  Explore"));
        assertTrue(hasText(root, "↓  Transfers"));
        assertTrue(hasText(root, "▣  Library"));
        assertTrue(hasText(root, "Find it. Save it."));
        assertTrue(hasText(root, "In motion"));
        assertTrue(hasText(root, "Discover media  →"));
    }

    @Test public void libraryShowsBrandAndEmptyState() {
        final HistoryActivity activity = Robolectric.buildActivity(HistoryActivity.class)
                .create().start().resume().get();
        final View root = activity.findViewById(android.R.id.content);
        assertNotNull(root);
        assertTrue(hasText(root, "Your collection"));
        assertTrue(hasText(root, "NOVA / YOUR LIBRARY"));
    }
}
