package com.example.ytdl;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local-only favorites. A record includes the exact saved MediaStore URI,
 * so a recycled task ID cannot accidentally favorite a different download.
 */
final class LibraryFavorites {
    private static final String PREFS = "nova_library_favorites";
    private static final String PREFIX = "saved-";

    private LibraryFavorites() {}

    static boolean eligible(DownloadService.Task task) {
        if (task == null || task.id <= 0
                || task.state != DownloadService.State.DONE
                || task.outputUri == null || task.outputUri.isEmpty()) return false;
        try {
            return PendingMediaLedger.isOwnedItemUri(Uri.parse(task.outputUri));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isFavorite(Context context, DownloadService.Task task) {
        return eligible(task) && task.outputUri.equals(
                preferences(context).getString(PREFIX + task.id, null));
    }

    /** Read preferences just once per Library snapshot, not once for every row. */
    static Set<Long> favoriteIds(Context context, List<DownloadService.Task> tasks) {
        if (tasks == null || tasks.isEmpty()) return Collections.emptySet();
        final Map<String, ?> saved = preferences(context).getAll();
        final Set<Long> result = new HashSet<>();
        for (DownloadService.Task task : tasks) {
            if (eligible(task) && task.outputUri.equals(saved.get(PREFIX + task.id))) {
                result.add(task.id);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    static boolean toggle(Context context, DownloadService.Task task) {
        if (!eligible(task)) return false;
        final String key = PREFIX + task.id;
        final SharedPreferences prefs = preferences(context);
        if (task.outputUri.equals(prefs.getString(key, null))) {
            prefs.edit().remove(key).apply();
            return false;
        }
        prefs.edit().putString(key, task.outputUri).apply();
        return true;
    }
}
