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
import okio.Buffer;
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

    @Test public void rejectsWrongFirstRangeOffsetWithoutWriting() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 1-3/4").setBody("BCD"));
        File file = new File(folder.getRoot(), "bad-first.part");
        try {
            Net.fetchToFile(server.url("/media").toString(), file, -1, progress, control);
            fail("Invalid first range accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("range mismatch"));
        }
        assertEquals(0L, file.length());
    }

    @Test public void rejectsMissingContentRangeOnPartialResponse() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(206).setBody("DATA"));
        File file = new File(folder.getRoot(), "missing-range.part");
        try {
            Net.fetchToFile(server.url("/media").toString(), file, -1, progress, control);
            fail("Missing Content-Range accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("range mismatch"));
        }
        assertEquals(0L, file.length());
    }

    @Test public void rejectsOverlongBodyBeforeItIsWritten() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-1/4").setBody("ABCD"));
        File file = new File(folder.getRoot(), "overrun.part");
        try {
            Net.fetchToFile(server.url("/media").toString(), file, -1, progress, control);
            fail("Oversized body accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("206 body length"));
        }
        assertEquals(0L, file.length());
    }

    @Test public void retriesTruncatedChunkedResponseFromCorrectOffset() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-3/4").setChunkedBody("AB", 1));
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 2-3/4").setBody("CD"));
        File file = new File(folder.getRoot(), "short-chunk.part");
        Net.fetchToFile(server.url("/media").toString(), file, -1, progress, control);
        assertEquals("ABCD", read(file));
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
        RecordedRequest resumed = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(resumed);
        assertTrue(resumed.getHeader("Range").startsWith("bytes=2-"));
    }

    @Test public void refusesMidstreamContentLengthChange() throws Exception {
        final int chunk = 4 * 1024 * 1024;
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-" + (chunk - 1)
                        + "/" + (chunk + 2))
                .setBody(new Buffer().write(new byte[chunk])));
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes " + chunk + "-" + (chunk + 1)
                        + "/" + (chunk + 3)).setBody("XY"));
        File file = new File(folder.getRoot(), "changed-total.part");
        try {
            Net.fetchToFile(server.url("/media").toString(), file, -1, progress, control);
            fail("Changed total length accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("stream length changed between chunks"));
        }
        assertEquals(chunk, file.length());
    }

    @Test public void rejects416ConflictingWithKnownPartialLength() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(416)
                .setHeader("Content-Range", "bytes */4"));
        File file = new File(folder.getRoot(), "conflicting-416.part");
        Files.write(file.toPath(), "AB".getBytes(StandardCharsets.UTF_8));
        try {
            Net.fetchToFile(server.url("/media").toString(), file, 2, progress, control);
            fail("Contradictory server total accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("HTTP 416 before partial was confirmed complete"));
        }
        assertEquals("AB", read(file));
    }


    @Test public void rejectsUnverified416EvenWhenLocalExpectedLengthMatches() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(416));
        File file = new File(folder.getRoot(), "unverified-416.part");
        Files.write(file.toPath(), "DONE".getBytes(StandardCharsets.UTF_8));
        try {
            Net.fetchToFile(server.url("/file").toString(), file, 4, progress, control);
            fail("Unverified 416 was treated as complete");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("HTTP 416 before partial"));
        }
        assertEquals("DONE", read(file));
    }

    @Test public void rejectsMalformedUnsatisfiedContentRange() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(416)
                .setHeader("Content-Range", "nonsense/4"));
        File file = new File(folder.getRoot(), "malformed-416.part");
        Files.write(file.toPath(), "DONE".getBytes(StandardCharsets.UTF_8));
        try {
            Net.fetchToFile(server.url("/file").toString(), file, 4, progress, control);
            fail("Malformed 416 total accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("HTTP 416 before partial"));
        }
    }

    @Test public void rejects206WithWrongRangeUnit() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "items 0-3/4").setBody("DATA"));
        File file = new File(folder.getRoot(), "bad-unit.part");
        try {
            Net.fetchToFile(server.url("/file").toString(), file, -1, progress, control);
            fail("Wrong range unit accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("range mismatch"));
        }
        assertEquals(0L, file.length());
    }

    @Test public void retriesHttp429AndSavesFile() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "0"));
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-3/4").setBody("DATA"));
        File file = new File(folder.getRoot(), "rate-limited.part");
        Net.fetchToFile(server.url("/file").toString(), file, -1, progress, control);
        assertEquals("DATA", read(file));
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
    }

    @Test public void validatesRetryAfterAndCapsWait() {
        assertEquals(2000L, Net.retryAfterMillis("2", 0));
        assertEquals(30000L, Net.retryAfterMillis("9999999999", 0));
        assertEquals(-1L, Net.retryAfterMillis("not-a-date", 0));
        assertEquals(-1L, Net.retryAfterMillis("-1", 0));
        assertEquals(0L, Net.retryAfterMillis(
                "Wed, 21 Oct 2015 07:28:00 GMT", 2000000000000L));
    }

    @Test public void rejectsUnknownLengthHttp200WhenExpectedSizeDiffers()
            throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setChunkedBody("ABC", 2));
        File file = new File(folder.getRoot(), "short-200.part");
        try {
            Net.fetchToFile(server.url("/file").toString(), file, 4, progress, control);
            fail("Unexpectedly short HTTP 200 accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("stream length mismatch"));
        }
        assertEquals("ABC", read(file));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
