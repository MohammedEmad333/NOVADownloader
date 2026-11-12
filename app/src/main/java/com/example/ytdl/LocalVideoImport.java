package com.example.ytdl;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies only a video explicitly chosen through Android's document picker.
 * No credentials, media-library scan or persistent broad storage permission.
 */
final class LocalVideoImport {
    private static final long MAX_VIDEO_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long RESERVE_BYTES = 32L * 1024 * 1024;

    static final class Imported {
        final Uri uri;
        final String name;
        final String mime;
        final long bytes;

        Imported(Uri uri, String name, String mime, long bytes) {
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.bytes = bytes;
        }
    }

    private LocalVideoImport() {}

    static boolean supportedMime(String mime) {
        return "video/mp4".equalsIgnoreCase(mime)
                || "video/webm".equalsIgnoreCase(mime)
                || "video/3gpp".equalsIgnoreCase(mime)
                || "video/quicktime".equalsIgnoreCase(mime)
                || "video/x-matroska".equalsIgnoreCase(mime);
    }

    static String safeName(String name, String mime) {
        String value = name == null ? "" : name.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
        value = value.replace("..", "_");
        if (value.length() > 120) value = value.substring(0, 120);
        if (value.isEmpty() || value.equals(".")) value = "NOVA-import";
        String suffix = "video/mp4".equalsIgnoreCase(mime) ? ".mp4"
                : "video/webm".equalsIgnoreCase(mime) ? ".webm"
                : "video/3gpp".equalsIgnoreCase(mime) ? ".3gp"
                : "video/quicktime".equalsIgnoreCase(mime) ? ".mov" : ".mkv";
        if (!value.toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) value += suffix;
        return value;
    }

    static Imported copy(Context context, Uri source) throws IOException {
        if (source == null || !"content".equals(source.getScheme())) {
            throw new IOException("Choose a video using Android's file picker");
        }
        final ContentResolver resolver = context.getContentResolver();
        final String mime = resolver.getType(source);
        if (!supportedMime(mime)) throw new IOException("Unsupported video format");
        final String name;
        final long expected;
        try (Cursor cursor = resolver.query(source,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("Could not inspect selected video");
            }
            final int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            final int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
            name = safeName(nameColumn >= 0 ? cursor.getString(nameColumn) : null, mime);
            expected = sizeColumn >= 0 && !cursor.isNull(sizeColumn)
                    ? cursor.getLong(sizeColumn) : -1;
        }
        if (expected > MAX_VIDEO_BYTES) throw new IOException("Selected video is too large");
        if (expected >= 0) {
            final long free = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
            // MediaStore may use a different volume; this is an early conservative check only.
            if (free < expected + RESERVE_BYTES) throw new IOException("Not enough free space");
        }

        final ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, mime);
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NOVA");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        final Uri target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (target == null) throw new IOException("Could not create local video");
        boolean published = false;
        try {
            final PendingMediaLedger ledger = new PendingMediaLedger(context);
            ledger.record(target);
            long copied = 0;
            try (InputStream in = resolver.openInputStream(source);
                 OutputStream out = resolver.openOutputStream(target, "w")) {
                if (in == null || out == null) throw new IOException("Cannot open video for import");
                final byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) {
                    copied += read;
                    if (copied > MAX_VIDEO_BYTES) throw new IOException("Video exceeds import limit");
                    out.write(buf, 0, read);
                }
            }
            if (copied == 0) throw new IOException("Selected video is empty");
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (resolver.update(target, values, null, null) <= 0) {
                throw new IOException("Could not finish importing video");
            }
            published = true;
            try {
                ledger.forget(target);
            } catch (IOException ignored) {
                // Already finalized: a stale ledger marker cannot delete an IS_PENDING=0 item.
            }
            return new Imported(target, name, mime, copied);
        } catch (Exception failure) {
            if (!published) {
                try {
                    resolver.delete(target, null, null);
                    new PendingMediaLedger(context).forget(target);
                } catch (Exception ignored) {
                    // The recorded row can be reviewed through Storage & Recovery later.
                }
            }
            throw failure instanceof IOException ? (IOException) failure
                    : new IOException("Could not import video", failure);
        }
    }
}
