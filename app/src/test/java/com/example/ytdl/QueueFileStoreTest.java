package com.example.ytdl;

import android.util.AtomicFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class QueueFileStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void roundTripsUtf8() throws Exception {
        QueueFileStore store = new QueueFileStore(new File(folder.getRoot(), "queue.json"));
        String content = "[{\"title\":\"مرحبا\",\"state\":\"PAUSED\"}]";
        store.write(content);
        assertEquals(content, store.read());
    }

    @Test public void shorterReplacementDoesNotLeaveOldSuffix() throws Exception {
        QueueFileStore store = new QueueFileStore(new File(folder.getRoot(), "queue.json"));
        store.write("[{\"a\":\"long content\"}]");
        store.write("[]");
        assertEquals("[]", store.read());
    }

    @Test public void interruptedReplacementRecoversLastSnapshot() throws Exception {
        File path = new File(folder.getRoot(), "queue.json");
        QueueFileStore store = new QueueFileStore(path);
        store.write("[1]");
        FileOutputStream unfinished = new AtomicFile(path).startWrite();
        unfinished.write("[2".getBytes(StandardCharsets.UTF_8));
        unfinished.close();
        assertEquals("[1]", store.read());
    }

    @Test public void failedReplacementRecoversLastSnapshot() throws Exception {
        File path = new File(folder.getRoot(), "queue.json");
        QueueFileStore store = new QueueFileStore(path);
        store.write("[1]");
        AtomicFile atom = new AtomicFile(path);
        FileOutputStream unfinished = atom.startWrite();
        unfinished.write("[2]".getBytes(StandardCharsets.UTF_8));
        atom.failWrite(unfinished);
        assertEquals("[1]", store.read());
    }
}
