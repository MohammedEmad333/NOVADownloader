package com.example.ytdl;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ListView;
import android.widget.FrameLayout;
import android.widget.BaseAdapter;

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
        // ListView keeps headers in its adapter until its first layout pass.
        // Inspect adapter headers too, so smoke tests represent the new
        // scrollable screen hierarchy without requiring a physical display.
        if (view instanceof ListView) {
            final ListView list = (ListView) view;
            for (int i = 0; i < list.getHeaderViewsCount(); i++) {
                if (hasText(list.getAdapter().getView(i, null, list), expected)) {
                    return true;
                }
            }
        }
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

    @Test public void bothMainPagesUseScrollableListsWithHeaders() {
        final MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().get();
        final View root = activity.findViewById(android.R.id.content);
        final java.util.List<ListView> lists = new java.util.ArrayList<>();
        collectLists(root, lists);
        assertEquals(2, lists.size());
        for (final ListView list : lists) {
            assertEquals(1, list.getHeaderViewsCount());
            assertTrue(list.getAdapter().getCount() > 0);
        }
    }

    private static void collectLists(final View node, final java.util.List<ListView> lists) {
        if (node instanceof ListView) lists.add((ListView) node);
        if (!(node instanceof ViewGroup)) return;
        final ViewGroup group = (ViewGroup) node;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectLists(group.getChildAt(i), lists);
        }
    }

    @Test public void listHeaderPositionNeverSelectsWrongFormat() {
        assertEquals(-1, MainActivity.optionIndexForListPosition(0, 1, 3));
        assertEquals(0, MainActivity.optionIndexForListPosition(1, 1, 3));
        assertEquals(2, MainActivity.optionIndexForListPosition(3, 1, 3));
        assertEquals(-1, MainActivity.optionIndexForListPosition(4, 1, 3));
        assertEquals(-1, MainActivity.optionIndexForListPosition(0, 1, 0));
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
