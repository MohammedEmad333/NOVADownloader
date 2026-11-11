package com.example.ytdl;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Safely replaces the queue while preserving the previous complete snapshot. */
final class QueueFileStore {
    private final AtomicFile file;

    QueueFileStore(final File path) {
        file = new AtomicFile(path);
    }

    String read() throws IOException {
        return new String(file.readFully(), StandardCharsets.UTF_8);
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
