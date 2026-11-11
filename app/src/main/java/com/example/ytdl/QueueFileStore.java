package com.example.ytdl;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Safely replaces the queue while preserving the previous complete snapshot. */
final class QueueFileStore {
    private final AtomicFile file;

    QueueFileStore(final File path) {
        file = new AtomicFile(path);
    }

    String read() throws IOException {
        return new String(file.readFully(), StandardCharsets.UTF_8);
    }

    /**
     * Preserve unreadable queue bytes outside Android cloud/device backups before an
     * eventual save can replace the corrupt file. Never overwrite an older recovery.
     */
    static File preserveUnreadable(final File original, final File recoveryDir)
            throws IOException {
        if (!original.isFile()) throw new IOException("Queue file is missing");
        if (!recoveryDir.isDirectory() && !recoveryDir.mkdirs()) {
            throw new IOException("Cannot create queue recovery directory");
        }
        final File recovery = File.createTempFile("queue-unreadable-", ".json", recoveryDir);
        try {
            Files.copy(original.toPath(), recovery.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return recovery;
        } catch (final IOException e) {
            recovery.delete();
            throw e;
        }
    }

    void write(final String contents) throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (final IOException | RuntimeException e) {
            if (output != null) file.failWrite(output);
            throw e;
        }
    }
}
