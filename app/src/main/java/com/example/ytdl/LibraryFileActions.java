package com.example.ytdl;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import java.util.Locale;

/**
 * Read-only external actions for completed NOVA MediaStore entries.
 * The queue's stored URI must have the expected local MediaStore item shape.
 * No file read, copy, modification, or deletion happens here.
 */
final class LibraryFileActions {
    private static final String DEFAULT_MIME = "application/octet-stream";

    private LibraryFileActions() {}

    static boolean eligible(DownloadService.Task task) {
        return LibraryFavorites.eligible(task);
    }

    static Intent viewIntent(DownloadService.Task task) {
        if (!eligible(task)) return null;
        final Uri uri = Uri.parse(task.outputUri);
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, safeMimeType(task.mimeType))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static Intent shareIntent(DownloadService.Task task) {
        if (!eligible(task)) return null;
        final Uri uri = Uri.parse(task.outputUri);
        return new Intent(Intent.ACTION_SEND)
                .setType(safeMimeType(task.mimeType))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .setClipData(ClipData.newRawUri("NOVA saved media", uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static String safeMimeType(String raw) {
        if (raw == null) return DEFAULT_MIME;
        final String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
                ? normalized : DEFAULT_MIME;
    }
}
