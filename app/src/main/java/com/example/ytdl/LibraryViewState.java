package com.example.ytdl;

import android.content.Context;
import android.content.SharedPreferences;

/** Device-local Library controls, independent of the download queue. */
final class LibraryViewState {
    static final int MAX_SEARCH_LENGTH = 200;
    private static final String PREFS = "nova_library_view";
    private static final String SEARCH = "search";
    private static final String CATEGORY = "category";
    private static final String SORT = "sort";
    private static final String SOURCE = "source";

    static final class State {
        final String search;
        final int category;
        final int sort;
        final int source;

        State(String search, int category, int sort, int source) {
            this.search = search;
            this.category = category;
            this.sort = sort;
            this.source = source;
        }
    }

    private LibraryViewState() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int within(int value, int maximum) {
        return value >= 0 && value <= maximum ? value : 0;
    }

    private static String limited(String text) {
        if (text == null) return "";
        return text.length() > MAX_SEARCH_LENGTH
                ? text.substring(0, MAX_SEARCH_LENGTH) : text;
    }

    static State load(Context context) {
        final SharedPreferences saved = prefs(context);
        return new State(limited(saved.getString(SEARCH, "")),
                within(saved.getInt(CATEGORY, 0), 6),
                within(saved.getInt(SORT, 0), 3),
                within(saved.getInt(SOURCE, 0), 6));
    }

    static void save(Context context, String search, int category, int sort, int source) {
        prefs(context).edit()
                .putString(SEARCH, limited(search))
                .putInt(CATEGORY, within(category, 6))
                .putInt(SORT, within(sort, 3))
                .putInt(SOURCE, within(source, 6))
                .apply();
    }
}
