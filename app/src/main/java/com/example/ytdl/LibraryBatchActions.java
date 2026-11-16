package com.example.ytdl;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only external sharing of explicitly selected completed MediaStore files. */
final class LibraryBatchActions {
    static final int MAX_SHARE = 50;
    private LibraryBatchActions() {}

    static Intent shareIntent(List<DownloadService.Task> files) {
        if (files == null || files.isEmpty() || files.size() > MAX_SHARE) return null;
        if (files.size() == 1) return LibraryFileActions.shareIntent(files.get(0));
        final ArrayList<Uri> uris = new ArrayList<>();
        final Set<String> distinct = new HashSet<>();
        String type = null;
        for (DownloadService.Task task : files) {
            if (!LibraryFileActions.eligible(task)) return null;
            if (!distinct.add(task.outputUri)) continue;
            uris.add(Uri.parse(task.outputUri));
            final String itemType = LibraryFileActions.safeMimeType(task.mimeType);
            if (type == null) type = itemType;
            else if (!type.equals(itemType)) type = "*/*";
        }
        if (uris.isEmpty()) return null;
        final ClipData clip = ClipData.newRawUri("NOVA saved media", uris.get(0));
        for (int i = 1; i < uris.size(); i++) {
            clip.addItem(new ClipData.Item(uris.get(i)));
        }
        final Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType(type == null ? "*/*" : type);
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.setClipData(clip);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }
}
