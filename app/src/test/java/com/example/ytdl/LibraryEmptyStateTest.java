package com.example.ytdl;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LibraryEmptyStateTest {
    @Test public void emptyLibraryPromptsToImportOrFinishADownload() {
        final LibraryEmptyState state = LibraryEmptyState.forFilters(0, "", 0, 0);
        assertEquals("Your library is empty", state.englishTitle);
        assertEquals("مكتبتك فارغة", state.arabicTitle);
        assertTrue(state.arabicHint.contains("استورد"));
    }

    @Test public void emptyResultsDistinguishSearchDuplicatesAndFavorites() {
        assertEquals("No matching files", LibraryEmptyState.forFilters(
                2, "something", 6, 0).englishTitle);
        assertEquals("لا توجد نسخ محتملة", LibraryEmptyState.forFilters(
                2, "", 6, 0).arabicTitle);
        assertEquals("No favorites here", LibraryEmptyState.forFilters(
                2, "", LibraryFilter.FAVORITES, 0).englishTitle);
        assertEquals("لا توجد ملفات من هذا المصدر", LibraryEmptyState.forFilters(
                2, "", 0, LibraryOrganizer.YOUTUBE).arabicTitle);
    }
}
