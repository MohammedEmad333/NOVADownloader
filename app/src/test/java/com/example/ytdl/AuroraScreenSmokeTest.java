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
public final class AuroraScreenSmokeTest {
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

    @Test public void mainScreenCreatesExploreAndDownloadsWithoutLayoutMutations() {
        final MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().get();
        final View root = activity.findViewById(android.R.id.content);
        assertNotNull(root);
        assertTrue(hasText(root, "Your media, organized."));
        assertTrue(hasText(root, "Find your next download"));
        assertTrue(hasText(root, "Download manager"));
        assertTrue(hasText(root, "Find media"));
    }

    @Test public void historyScreenCreatesBrandAndEmptyLibrary() {
        final HistoryActivity activity = Robolectric.buildActivity(HistoryActivity.class)
                .create().start().resume().get();
        final View root = activity.findViewById(android.R.id.content);
        assertNotNull(root);
        assertTrue(hasText(root, "Saved & remembered"));
        assertTrue(hasText(root, "AURORA / YOUR LIBRARY"));
    }
}
