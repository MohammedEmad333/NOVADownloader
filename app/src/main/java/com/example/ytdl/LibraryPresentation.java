package com.example.ytdl;

/** Pure adaptive Library layout rules, using Android's available width in dp. */
final class LibraryPresentation {
    private LibraryPresentation() {}

    static int gridColumns(int widthDp) {
        if (widthDp >= 720) return 3;
        if (widthDp >= 360) return 2;
        return 1;
    }

    /** Reveal advanced choices on reopening if they cannot be seen from quick chips. */
    static boolean expandAdvancedFilters(int category, int sort, int source) {
        return sort != 0 || source != 0 || category == LibraryFilter.SUBTITLES
                || category == 6;
    }
}
