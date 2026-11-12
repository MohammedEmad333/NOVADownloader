package com.example.ytdl;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class PlaylistToolsTest {
    @Test public void duplicateUrlsAreRemovedWithoutChangingOrdering() {
        final List<String> inputs = Arrays.asList("a", "b", "a", "c", "b");
        assertEquals(Arrays.asList("a", "b", "c"),
                PlaylistTools.uniqueByUrl(inputs, item -> item));
    }

    @Test public void blankUrlsAreIgnored() {
        assertEquals(Arrays.asList("a"),
                PlaylistTools.uniqueByUrl(Arrays.asList(null, " ", "a", " a "),
                        item -> item));
    }

    @Test public void searchingRetainsOriginalIndices() {
        assertEquals(Arrays.asList(0, 2),
                PlaylistTools.matches(Arrays.asList("First song", "Movie", "another SONG"),
                        " song "));
    }

    @Test public void selectVisibleDoesNotAffectHiddenEntries() {
        final boolean[] checked = new boolean[]{true, false, false, true};
        final List<Integer> visible = Arrays.asList(1, 2);
        assertFalse(PlaylistTools.allSelected(checked, visible));
        PlaylistTools.selectVisible(checked, visible, true);
        assertArrayEquals(new boolean[]{true, true, true, true}, checked);
        assertTrue(PlaylistTools.allSelected(checked, visible));
        PlaylistTools.selectVisible(checked, visible, false);
        assertArrayEquals(new boolean[]{true, false, false, true}, checked);
    }

    @Test public void emptySearchShowsEntireCollection() {
        assertEquals(Arrays.asList(0, 1),
                PlaylistTools.matches(Arrays.asList("A", "B"), ""));
    }
}
