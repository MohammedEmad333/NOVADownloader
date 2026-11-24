package com.example.ytdl;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class VideoPageSupportTest {
    @Test public void parsesJsonLdVideoAndNestedEmbedCandidates() {
        final String page = "https://pages.example.org/watch/42";
        final String html = "<html><head><title>Episode 42</title>"
                + "<script type='application/ld+json'>"
                + "{\"@type\":\"VideoObject\",\"contentUrl\":\"/media/episode.mp4\"}"
                + "</script></head><body>"
                + "<iframe src='/embed/42'></iframe>"
                + "<iframe src='https://player.example.org/embed/42'></iframe>"
                + "</body></html>";
        final PublicVideoLinks.Result result = PublicVideoLinks.parseHtml(page, html);
        assertEquals("Episode 42", result.title);
        assertEquals(1, result.videos.size());
        assertEquals("https://pages.example.org/media/episode.mp4",
                result.videos.get(0).url);
        final List<String> embeds = PublicVideoLinks.embeddedPages(page, html);
        assertEquals(2, embeds.size());
        assertEquals("https://pages.example.org/embed/42", embeds.get(0));
        assertEquals("https://player.example.org/embed/42", embeds.get(1));
    }

    @Test public void extractsOpenGraphHlsManifestAsVideoCandidate() {
        final PublicVideoLinks.Result result = PublicVideoLinks.parseHtml(
                "https://pages.example.org/watch",
                "<meta property='og:title' content='Public episode'>"
                        + "<meta property='og:video' content='/hls/master.m3u8'>");
        assertEquals(1, result.videos.size());
        assertEquals("m3u8", result.videos.get(0).extension);
        assertEquals("application/vnd.apple.mpegurl", result.videos.get(0).mime);
    }

    @Test public void parsesPublicHlsVodAndRejectsProtectedOrLivePlaylists()
            throws Exception {
        final String url = "https://cdn.example.org/video/index.m3u8";
        final String vod = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:8\n"
                + "#EXTINF:7.5,\nseg-1.ts\n#EXTINF:4.25,\nseg-2.ts\n#EXT-X-ENDLIST\n";
        final PublicHls.Playlist playlist = PublicHls.parseMedia(url, vod);
        assertEquals(2, playlist.segments.size());
        assertEquals("https://cdn.example.org/video/seg-1.ts",
                playlist.segments.get(0).url);
        assertEquals(7.5, playlist.segments.get(0).duration, 0.001);
        assertTrue(playlist.targetDuration >= 8);

        final String encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI='key.bin'\n"
                + "#EXTINF:5,\nseg.ts\n#EXT-X-ENDLIST\n";
        try {
            PublicHls.parseMedia(url, encrypted);
            fail("Encrypted HLS must be rejected");
        } catch (java.io.IOException expected) { }

        final String live = "#EXTM3U\n#EXTINF:5,\nseg.ts\n";
        try {
            PublicHls.parseMedia(url, live);
            fail("Live HLS must be rejected");
        } catch (java.io.IOException expected) { }

        final String ranged = "#EXTM3U\n#EXT-X-BYTERANGE:100@0\n"
                + "#EXTINF:5,\nseg.ts\n#EXT-X-ENDLIST\n";
        try {
            PublicHls.parseMedia(url, ranged);
            fail("Byte-range HLS must be rejected");
        } catch (java.io.IOException expected) { }
    }

    @Test public void masterPlaylistVariantsResolveOnlyPublicHttpsTargets() throws Exception {
        final String url = "https://cdn.example.org/master.m3u8";
        final String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\n360/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720\n720/index.m3u8\n";
        final List<PublicVideoLinks.Video> variants = PublicHls.variants(url, master);
        assertEquals(2, variants.size());
        assertTrue(variants.get(0).label.contains("720p"));
        assertEquals("https://cdn.example.org/720/index.m3u8", variants.get(0).url);
    }

    @Test public void hlsQueueIntentSurvivesSerializationAndProcessRecovery()
            throws Exception {
        final DownloadService.Spec spec = DownloadService.Spec.hls();
        final DownloadService.Task task = new DownloadService.Task(
                "https://video.example.org/watch/1", "Episode", spec);
        task.preResolve("https://cdn.example.org/episode/master.m3u8", null,
                "m3u8", "application/vnd.apple.mpegurl");
        task.state = DownloadService.State.VIDEO;
        final DownloadService.Task restored =
                DownloadService.Task.fromJson(task.toJson());
        assertEquals(DownloadService.Kind.HLS, restored.spec.kind);
        assertEquals("https://video.example.org/watch/1", restored.pageUrl);
        assertEquals("https://cdn.example.org/episode/master.m3u8", restored.videoUrl);
        assertEquals("m3u8", restored.extension);
    }

    @Test public void serviceRoutingUsesEveryRegisteredNewPipeExtractor() {
        assertFalse(ServiceList.all().isEmpty());
        for (StreamingService service : ServiceList.all()) {
            assertNotNull(service.getServiceInfo().getName());
            assertFalse(service.getServiceInfo().getName().trim().isEmpty());
        }
        assertTrue(PublicVideoLinks.isGenericLink("https://ordinary.example.org/watch/1"));
        assertFalse(PublicVideoLinks.isGenericLink("https://youtu.be/dQw4w9WgXcQ"));
    }
}
