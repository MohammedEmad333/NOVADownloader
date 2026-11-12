package com.example.ytdl;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Crash-recovery ledger for MediaStore rows created by this app.
 *
 * Only exact URIs returned by this app's MediaStore.insert are recorded. On normal
 * completion or successful rollback they are removed. After a process crash,
 * the user may inspect old, still-pending rows and explicitly delete them.
 * Completed (IS_PENDING=0) rows are NEVER deleted, even if their ledger entry
 * was left behind by a crash after finalization.
 *
 * The ledger lives in noBackupFilesDir because MediaStore row IDs are device-local.
 */
final class PendingMediaLedger {
    private static final String TAG = "PendingMediaLedger";
    private static final String FILE_NAME = "pending-media-ledger.json";
    static final long MIN_CLEANUP_AGE_MS = 60L * 60 * 1000;
    private static final Object IO_LOCK = new Object();

    static final class Entry {
        final String uri;
        final long createdAt;

        Entry(String uri, long createdAt) {
            this.uri = uri;
            this.createdAt = createdAt;
        }
    }

    static final class Scan {
        final int tracked;
        final int eligible;
        final int recent;
        final int unknown;

        Scan(int tracked, int eligible, int recent, int unknown) {
            this.tracked = tracked;
            this.eligible = eligible;
            this.recent = recent;
            this.unknown = unknown;
        }
    }

    static final class Cleanup {
        final int rowsDeleted;
        final int recordsReconciled;
        final int failures;
        final boolean blockedByTransfer;

        Cleanup(int rowsDeleted, int recordsReconciled, int failures,
                boolean blockedByTransfer) {
            this.rowsDeleted = rowsDeleted;
            this.recordsReconciled = recordsReconciled;
            this.failures = failures;
            this.blockedByTransfer = blockedByTransfer;
        }
    }

    private final AtomicFile file;

    PendingMediaLedger(Context context) {
        this(new File(context.getNoBackupFilesDir(), FILE_NAME));
    }

    PendingMediaLedger(File path) {
        file = new AtomicFile(path);
    }

    /** Only this app's Downloads and Audio MediaStore item URIs are accepted. */
    static boolean isOwnedItemUri(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())
                || !"media".equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null) return false;
        final List<String> parts = uri.getPathSegments();
        if (parts == null || parts.size() < 3 || parts.get(0).isEmpty()) return false;
        final boolean downloads = parts.size() == 3 && "downloads".equals(parts.get(1));
        final boolean audio = parts.size() == 4
                && "audio".equals(parts.get(1)) && "media".equals(parts.get(2));
        if (!downloads && !audio) return false;
        final String id = parts.get(parts.size() - 1);
        if (id.isEmpty() || id.charAt(0) == '0') return false;
        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) < '0' || id.charAt(i) > '9') return false;
        }
        return true;
    }

    static boolean eligible(Entry entry, long now, int pendingState) {
        return entry != null && isOwnedItemUri(Uri.parse(entry.uri))
                && entry.createdAt > 0 && now >= entry.createdAt
                && now - entry.createdAt >= MIN_CLEANUP_AGE_MS
                && pendingState == 1;
    }

    void record(Uri uri) throws IOException {
        record(uri, System.currentTimeMillis());
    }

    void record(Uri uri, long time) throws IOException {
        if (!isOwnedItemUri(uri) || time <= 0) throw new IOException("Unexpected MediaStore URI");
        synchronized (IO_LOCK) {
            final List<Entry> entries = read();
            for (Entry entry : entries) {
                if (entry.uri.equals(uri.toString())) return;
            }
            entries.add(new Entry(uri.toString(), time));
            write(entries);
        }
    }

    void forget(Uri uri) throws IOException {
        if (!isOwnedItemUri(uri)) return;
        synchronized (IO_LOCK) {
            final List<Entry> entries = read();
            if (entries.removeIf(entry -> entry.uri.equals(uri.toString()))) write(entries);
        }
    }

    List<Entry> snapshot() throws IOException {
        synchronized (IO_LOCK) {
            return read();
        }
    }

    Scan inspect(ContentResolver resolver, long now) throws IOException {
        final List<Entry> entries = snapshot();
        int eligible = 0, recent = 0, unknown = 0;
        for (Entry entry : entries) {
            if (!isOwnedItemUri(Uri.parse(entry.uri))) {
                unknown++;
                continue;
            }
            final int state = pendingState(resolver, Uri.parse(entry.uri));
            if (eligible(entry, now, state)) eligible++;
            else if (state == 1) recent++;
            else if (state == -2) unknown++;
        }
        return new Scan(entries.size(), eligible, recent, unknown);
    }

    /** Provider verification is mandatory. Never delete untracked or finished files. */
    Cleanup cleanup(ContentResolver resolver, long now, boolean workerRunning,
                    ReentrantLock workerLock) throws IOException {
        if (!workerLock.tryLock()) return new Cleanup(0, 0, 0, true);
        try {
            if (workerRunning) return new Cleanup(0, 0, 0, true);
            synchronized (IO_LOCK) {
                final List<Entry> entries = read();
                final List<Entry> keep = new ArrayList<>();
                int deleted = 0, reconciled = 0, failed = 0;
                for (Entry entry : entries) {
                    final Uri uri = Uri.parse(entry.uri);
                    if (!isOwnedItemUri(uri)) {
                        keep.add(entry);
                        failed++;
                        continue;
                    }
                    final int state = pendingState(resolver, uri);
                    if (state == 0 || state == -1) {
                        // Completed or no longer present: clean only our ledger marker.
                        reconciled++;
                    } else if (eligible(entry, now, state)) {
                        try {
                            if (resolver.delete(uri, null, null) == 1) {
                                deleted++;
                            } else {
                                keep.add(entry);
                                failed++;
                            }
                        } catch (RuntimeException denied) {
                            Log.w(TAG, "Could not remove recorded unfinished MediaStore entry", denied);
                            keep.add(entry);
                            failed++;
                        }
                    } else {
                        keep.add(entry);
                        if (state == -2) failed++;
                    }
                }
                if (deleted > 0 || reconciled > 0) write(keep);
                return new Cleanup(deleted, reconciled, failed, false);
            }
        } finally {
            workerLock.unlock();
        }
    }

    static Scan inspect(Context context) throws IOException {
        return new PendingMediaLedger(context).inspect(
                context.getContentResolver(), System.currentTimeMillis());
    }

    static Cleanup cleanup(Context context) throws IOException {
        final ReentrantLock guard = DownloadService.CACHE_MAINTENANCE_LOCK;
        if (!guard.tryLock()) return new Cleanup(0, 0, 0, true);
        try {
            // No work may be in progress while deleting a recorded pending row.
            return new PendingMediaLedger(context).cleanup(context.getContentResolver(),
                    System.currentTimeMillis(), DownloadService.isWorkerRunning(), guard);
        } finally {
            guard.unlock();
        }
    }

    /**
     * -1 means the row is gone; -2 means it could not be verified. Both states
     * are safe: deletion is allowed ONLY when the provider explicitly returns 1.
     */
    private static int pendingState(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(MediaStore.setIncludePending(uri),
                new String[]{MediaStore.MediaColumns.IS_PENDING}, null, null, null)) {
            if (cursor == null) return -2;
            if (!cursor.moveToFirst()) return -1;
            final int col = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING);
            if (col < 0 || cursor.isNull(col)) return -2;
            final int value = cursor.getInt(col);
            return value == 0 || value == 1 ? value : -2;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not inspect recorded MediaStore item", error);
            return -2;
        }
    }

    private List<Entry> read() throws IOException {
        final List<Entry> entries = new ArrayList<>();
        final byte[] bytes;
        try {
            bytes = file.readFully();
        } catch (FileNotFoundException missing) {
            return entries;
        }
        try {
            final JSONArray array = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                final JSONObject item = array.getJSONObject(i);
                final Entry entry = new Entry(item.getString("uri"), item.getLong("createdAt"));
                if (!isOwnedItemUri(Uri.parse(entry.uri)) || entry.createdAt <= 0) {
                    throw new JSONException("Invalid ledger entry");
                }
                entries.add(entry);
            }
            return entries;
        } catch (JSONException invalid) {
            // Never silently replace the evidence of an unreadable ledger.
            throw new IOException("Pending media ledger is unreadable", invalid);
        }
    }

    private void write(List<Entry> entries) throws IOException {
        final JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                final JSONObject item = new JSONObject();
                item.put("uri", entry.uri);
                item.put("createdAt", entry.createdAt);
                array.put(item);
            }
        } catch (JSONException failure) {
            throw new IOException("Could not encode pending media ledger", failure);
        }
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException | RuntimeException failure) {
            if (out != null) file.failWrite(out);
            throw failure;
        }
    }
}
