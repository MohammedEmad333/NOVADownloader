package com.example.ytdl;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LibraryPresentationTest {
    @Test public void adaptsGridWithoutCrowdingNarrowScreens() {
        assertEquals(1, LibraryPresentation.gridColumns(0));
        assertEquals(1, LibraryPresentation.gridColumns(320));
        assertEquals(2, LibraryPresentation.gridColumns(360));
        assertEquals(2, LibraryPresentation.gridColumns(600));
        assertEquals(3, LibraryPresentation.gridColumns(720));
    }

    @Test public void restoresVisibleAdvancedChoicesWhenNeeded() {
        assertFalse(LibraryPresentation.expandAdvancedFilters(0, 0, 0));
        assertFalse(LibraryPresentation.expandAdvancedFilters(LibraryFilter.FAVORITES, 0, 0));
        assertTrue(LibraryPresentation.expandAdvancedFilters(0, 2, 0));
        assertTrue(LibraryPresentation.expandAdvancedFilters(0, 0, LibraryOrganizer.YOUTUBE));
        assertTrue(LibraryPresentation.expandAdvancedFilters(LibraryFilter.SUBTITLES, 0, 0));
        assertTrue(LibraryPresentation.expandAdvancedFilters(6, 0, 0));
    }
}
