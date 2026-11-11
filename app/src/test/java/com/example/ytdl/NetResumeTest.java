package com.example.ytdl;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowStatFs;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NetResumeTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private MockWebServer server;
    private final Net.Progress progress = (done, total) -> {};
    private final Net.Control control = () -> {};

    @Before public void setup() throws Exception {
        // Robolectric reports zero free blocks unless filesystem stats are registered.
        // Keep this isolated to tests; production's real storage guard is unchanged.
        ShadowStatFs.registerStats(folder.getRoot(), 262144, 262144, 262144);
        server = new MockWebServer();
        server.start();
    }

    @After public void cleanup() throws Exception {
        server.shutdown();
    }

    @Test public void startsWithRangeRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-3/4").setBody("DATA"));
        File file = new File(folder.getRoot(), "first.part");
        Net.fetchToFile(server.url("/media").toString(), file, -1, progress, control);
        assertEquals("DATA", read(file));
        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(request.getHeader("Range").startsWith("bytes=0-"));
    }

    @Test public void resumesAtPartialOffset() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 2-3/4").setBody("CD"));
        File file = new File(folder.getRoot(), "resume.part");
        Files.write(file.toPath(), "AB".getBytes(StandardCharsets.UTF_8));
        Net.fetchToFile(server.url("/media").toString(), file, 4, progress, control);
        assertEquals("ABCD", read(file));
        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(request.getHeader("Range").startsWith("bytes=2-"));
    }

    @Test public void rejectsWrongResumeOffset() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-1/4").setBody("CD"));
        File file = new File(folder.getRoot(), "bad.part");
        Files.write(file.toPath(), "AB".getBytes(StandardCharsets.UTF_8));
        try {
            Net.fetchToFile(server.url("/media").toString(), file, 4, progress, control);
            fail("Wrong offset accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("resume range mismatch"));
        }
        assertEquals("AB", read(file));
    }

    @Test public void rejectsTruncatedPartialWithHttp416() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(416)
                .setHeader("Content-Range", "bytes */4"));
        File file = new File(folder.getRoot(), "truncated.part");
        Files.write(file.toPath(), "AB".getBytes(StandardCharsets.UTF_8));
        try {
            Net.fetchToFile(server.url("/media").toString(), file, 4, progress, control);
            fail("Incomplete file accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("HTTP 416 before partial was confirmed complete"));
        }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
