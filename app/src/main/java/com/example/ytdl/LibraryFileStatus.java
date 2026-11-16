package com.example.ytdl;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only availability checks for completed, trusted MediaStore references.
 * A missing or inaccessible file never causes queue or media deletion.
 * Scans must run off the main thread.
 */
final class LibraryFileStatus {
    enum State { PRESENT, MISSING, NO_ACCESS, UNKNOWN }

    interface Probe {
        State check(Uri uri);
    }

    private LibraryFileStatus() {}

    static boolean unavailable(State state) {
        return state == State.MISSING || state == State.NO_ACCESS;
    }

    /** Opening a read-only descriptor verifies the file without reading its bytes. */
    static State check(ContentResolver resolver, Uri uri) {
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            return descriptor == null ? State.UNKNOWN : State.PRESENT;
        } catch (FileNotFoundException missing) {
            return State.MISSING;
        } catch (SecurityException denied) {
            return State.NO_ACCESS;
        } catch (IOException | RuntimeException uncertain) {
            return State.UNKNOWN;
        }
    }

    /** Deduplicate URI checks and avoid any access to unfinished or unsafe entries. */
    static Map<String, State> scan(List<DownloadService.Task> tasks, Probe probe) {
        if (tasks == null || probe == null) return Collections.emptyMap();
        final Map<String, State> checked = new LinkedHashMap<>();
        for (DownloadService.Task task : tasks) {
            if (Thread.currentThread().isInterrupted()) break;
            if (!LibraryFileActions.eligible(task)
                    || checked.containsKey(task.outputUri)) continue;
            State state;
            try {
                state = probe.check(Uri.parse(task.outputUri));
            } catch (SecurityException denied) {
                state = State.NO_ACCESS;
            } catch (RuntimeException uncertain) {
                state = State.UNKNOWN;
            }
            checked.put(task.outputUri, state == null ? State.UNKNOWN : state);
        }
        return Collections.unmodifiableMap(checked);
    }
}
