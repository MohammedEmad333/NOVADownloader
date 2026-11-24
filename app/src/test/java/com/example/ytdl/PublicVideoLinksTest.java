package com.example.ytdl;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.net.InetAddress;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class PublicVideoLinksTest {
    @Test public void directVideoLinksSupportQueryParametersAndMimeTypes() {
        final PublicVideoLinks.Video mp4 = PublicVideoLinks.video(
                "https://cdn.example.org/video.mp4?token=abc%3D123", null);
        assertNotNull(mp4);
        assertEquals("mp4", mp4.extension);
        assertEquals("video/mp4", mp4.mime);
        assertEquals("https://cdn.example.org/video.mp4?token=abc%3D123", mp4.url);
        assertNotNull(PublicVideoLinks.video("https://cdn.example.org/movie.webm",
                "video/webm"));
        final PublicVideoLinks.Video hls = PublicVideoLinks.video(
                "https://cdn.example.org/index.m3u8", null);
        assertNotNull(hls);
        assertEquals("m3u8", hls.extension);
        assertNull(PublicVideoLinks.video("https://cdn.example.org/movie.mp4",
                "text/html"));
        assertNull(PublicVideoLinks.video("javascript:alert(1)", "video/mp4"));
        assertNull(PublicVideoLinks.video("file:///private/video.mp4", null));
    }

    @Test public void parsesPublicHtml5SourcesAndRelativeMetaVideo() {
        final String page = "https://clips.example.org/posts/12";
        final String html = "<html><head>"
                + "<meta property='og:title' content='My &amp; Friends' />"
                + "<meta content='/clips/a.mp4?quality=720&amp;id=5' property='og:video' />"
                + "</head><body><video controls src='../clips/a.mp4?quality=720&amp;id=5'>"
                + "<source src='https://media.example.org/b.webm' type='video/webm'>"
                + "<source src='https://media.example.org/secret.m3u8' type='application/x-mpegURL'>"
                + "</video></body></html>";
        final PublicVideoLinks.Result result = PublicVideoLinks.parseHtml(page, html);
        assertEquals("My & Friends", result.title);
        assertEquals(3, result.videos.size());
        assertEquals("https://clips.example.org/clips/a.mp4?quality=720&id=5",
                result.videos.get(0).url);
        assertEquals("video/webm", result.videos.get(1).mime);
        assertEquals("m3u8", result.videos.get(2).extension);
    }

    @Test public void parsesHtml5VideoWithTypedExtensionlessSource() {
        final PublicVideoLinks.Result result = PublicVideoLinks.parseHtml(
                "https://video.example.org/page",
                "<title>A public clip</title>"
                        + "<video><source type=\"video/mp4\" src=\"/stream/123?session=abc\">"
                        + "</video>");
        assertEquals("A public clip", result.title);
        assertEquals(1, result.videos.size());
        assertEquals("mp4", result.videos.get(0).extension);
    }

    @Test public void rejectsUnsupportedOrUnsafePageSources() {
        final String html = "<title>No accessible video</title>"
                + "<video src='blob:https://example.org/id'>"
                + "<source src='file:///secret.mp4'>"
                + "<source src='http://localhost/admin.mp4'>"
                + "<source src='https://cdn.example.org/mix.mp4' type='text/html'>"
                + "<iframe src='https://example.org/embed'></iframe>";
        assertTrue(PublicVideoLinks.parseHtml("https://example.org/post", html)
                .videos.isEmpty());
    }

    @Test public void rejectsPrivateAndReservedAddresses() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "192.168.1.1",
                "10.0.0.1", "172.16.1.1", "169.254.1.1",
                "100.64.0.1", "198.18.0.1", "::1", "fc00::1"}) {
            assertFalse(ip, PublicVideoLinks.isPublicAddress(InetAddress.getByName(ip)));
        }
        assertTrue(PublicVideoLinks.isPublicAddress(InetAddress.getByName("1.1.1.1")));
        assertTrue(PublicVideoLinks.isPublicAddress(
                InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test public void shareParserAcceptsPublicWebLinkWithoutChangingExistingSources() {
        final String url = "https://media.example.org/watch/demo";
        assertEquals(url, LinkParser.firstSupportedMediaLink(
                "Look at this video: " + url + "."));
        assertEquals("https://media.example.org/test.mp4",
                LinkParser.firstSupportedMediaLink(
                        "https://media.example.org/test.mp4"));
        assertNull(LinkParser.firstSupportedMediaLink(
                "file:///data/user/secret.mp4"));
    }

    @Test public void queueSerializesSourceSelectionAndRestoresWithoutDataLoss()
            throws Exception {
        final DownloadService.Spec spec = DownloadService.Spec.direct(1, "WEBM · 2");
        final DownloadService.Task task = new DownloadService.Task(
                "https://site.example.org/watch", "My video", spec);
        task.preResolve("https://cdn.example.org/second.webm", null,
                "webm", "video/webm");
        task.state = DownloadService.State.VIDEO;
        task.expectedVideoBytes = 5097;
        task.done = 1024;
        final JSONObject json = task.toJson();
        final DownloadService.Task restored = DownloadService.Task.fromJson(json);
        assertEquals(DownloadService.Kind.DIRECT, restored.spec.kind);
        assertEquals(1, restored.spec.height);
        assertEquals("https://site.example.org/watch", restored.pageUrl);
        assertEquals("https://cdn.example.org/second.webm", restored.videoUrl);
        assertEquals(5097, restored.expectedVideoBytes);
        assertEquals(1024, restored.done);
        assertEquals(DownloadService.State.PAUSED, restored.state);
        try {
            DownloadService.Spec.direct(12, "not allowed");
            fail("Expected invalid source index");
        } catch (IllegalArgumentException expected) {
            // Limits discovered sources and durable variant identifiers.
        }
    }
}
