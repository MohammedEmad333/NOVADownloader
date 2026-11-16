package com.example.ytdl;

import android.content.Context;
import android.widget.EditText;
import android.widget.Spinner;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LibraryViewStateTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Test public void queryAndAllThreeSelectionsSurviveReopening() {
        try {
            LibraryViewState.save(context, "  My Track  ", 6, 3, 4);
            final LibraryViewState.State saved = LibraryViewState.load(context);
            assertEquals("  My Track  ", saved.search);
            assertEquals(6, saved.category);
            assertEquals(3, saved.sort);
            assertEquals(4, saved.source);

            final HistoryActivity screen = Robolectric.buildActivity(HistoryActivity.class)
                    .create().start().resume().get();
            final List<Spinner> selectors = new ArrayList<>();
            final List<EditText> searches = new ArrayList<>();
            findControls(screen.findViewById(android.R.id.content), selectors, searches);
            assertEquals(3, selectors.size());
            assertEquals(1, searches.size());
            assertEquals(saved.search, searches.get(0).getText().toString());
            assertEquals(6, selectors.get(0).getSelectedItemPosition());
            assertEquals(3, selectors.get(1).getSelectedItemPosition());
            assertEquals(4, selectors.get(2).getSelectedItemPosition());
            screen.finish();
        } finally {
            LibraryViewState.save(context, "", 0, 0, 0);
        }
    }

    private static void findControls(View view, List<Spinner> selectors,
                                     List<EditText> searches) {
        if (view instanceof Spinner) selectors.add((Spinner) view);
        if (view instanceof EditText) searches.add((EditText) view);
        if (!(view instanceof ViewGroup)) return;
        final ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            findControls(group.getChildAt(i), selectors, searches);
        }
    }

    @Test public void invalidSelectionsResetAndLongSearchIsBounded() {
        try {
            LibraryViewState.save(context, "x".repeat(300), -7, 99, 500);
            final LibraryViewState.State restored = LibraryViewState.load(context);
            assertEquals(200, restored.search.length());
            assertEquals(0, restored.category);
            assertEquals(0, restored.sort);
            assertEquals(0, restored.source);
        } finally {
            LibraryViewState.save(context, "", 0, 0, 0);
        }
    }
}
