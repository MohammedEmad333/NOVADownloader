package com.example.ytdl;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class TwitterHlsTest {
    private static final String BASE =
            "https://video.twimg.com/ext_tw_video/123/pu/pl/index.m3u8";
    private static final String MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:5\n"
            + "#EXTINF:5.0,\nsegment0.ts\n"
            + "#EXTINF:4.0,\nsegment1.ts?tag=12\n"
            + "#EXT-X-ENDLIST\n";

    private static void reject(String content) {
        try {
            TwitterHls.parseMedia(BASE, content);
            fail("Unsupported HLS playlist was accepted");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test public void acceptsOnlyHttpsTwitterCdnPlaylistsAndSegments() {
        assertTrue(TwitterVideo.isHlsUrl(BASE));
        assertTrue(TwitterHls.safeSegment("https://video.twimg.com/path/segment.ts?tag=3"));
        assertFalse(TwitterVideo.isHlsUrl("http://video.twimg.com/path/index.m3u8"));
        assertFalse(TwitterVideo.isHlsUrl("https://video.twimg.com.evil.invalid/index.m3u8"));
        assertFalse(TwitterHls.safeSegment("https://other.example/path/0.ts"));
        assertFalse(TwitterHls.safeSegment("https://video.twimg.com/path/0.m3u8"));
        assertFalse(TwitterVideo.isHlsUrl("https://user@video.twimg.com/playlist.m3u8"));
    }

    @Test public void mediaPlaylistResolvesRelativeSegmentsAndKeepsOrder()
            throws Exception {
        final TwitterHls.Playlist playlist = TwitterHls.parseMedia(BASE, MEDIA);
        assertEquals(2, playlist.segments.size());
        assertEquals("https://video.twimg.com/ext_tw_video/123/pu/pl/segment0.ts",
                playlist.segments.get(0).url);
        assertEquals("https://video.twimg.com/ext_tw_video/123/pu/pl/segment1.ts?tag=12",
                playlist.segments.get(1).url);
        assertEquals(64, playlist.fingerprint.length());
        assertEquals(playlist.fingerprint, TwitterHls.parseMedia(BASE, MEDIA).fingerprint);
        assertNotEquals(playlist.fingerprint, TwitterHls.parseMedia(BASE,
                MEDIA.replace("segment1.ts", "other.ts")).fingerprint);
    }

    @Test public void masterPlaylistPicksHighestBandwidthVariant()
            throws Exception {
        final String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=200000\nlow.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=900000\nhi.m3u8\n";
        assertEquals("https://video.twimg.com/ext_tw_video/123/pu/pl/hi.m3u8",
                TwitterHls.selectVariant(BASE, master));
        assertNull(TwitterHls.selectVariant(BASE, MEDIA));
    }

    @Test public void rejectsUntrustedVariantOrSegmentHosts() {
        try {
            TwitterHls.resolve(BASE, "https://example.net/video.ts");
            fail("Untrusted HLS segment allowed");
        } catch (IOException expected) {}
        try {
            TwitterHls.selectVariant(BASE,
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=20\n"
                    + "https://video.twimg.com.evil.invalid/top.m3u8\n");
            fail("Untrusted variant allowed");
        } catch (IOException expected) {}
    }

    @Test public void rejectsEncryptionOrKeyRequests() {
        reject(MEDIA.replace("#EXTINF:5.0,",
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key.key\"\n#EXTINF:5.0,"));
        reject(MEDIA.replace("#EXTINF:5.0,",
                "#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"key.key\"\n#EXTINF:5.0,"));
    }

    @Test public void rejectsLiveByteRangeDiscontinuityAndFragmentedMp4() {
        reject(MEDIA.replace("#EXT-X-ENDLIST", ""));
        reject(MEDIA.replace("#EXTINF:5.0,", "#EXT-X-BYTERANGE:100@0\n#EXTINF:5.0,"));
        reject(MEDIA.replace("#EXTINF:4.0,", "#EXT-X-DISCONTINUITY\n#EXTINF:4.0,"));
        reject(MEDIA.replace("#EXTINF:5.0,",
                "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:5.0,"));
        reject(MEDIA.replace("#EXTINF:4.0,", "#EXT-X-GAP\n#EXTINF:4.0,"));
    }

    @Test public void rejectsMalformedOrExtraPlaylistSegments() {
        reject("#EXTM3U\n#EXTINF:5.0,\nsegment0.ts\n");
        reject("#EXTM3U\nsegment0.ts\n#EXT-X-ENDLIST");
        reject("#EXTM3U\n#EXTINF:5.0,\n"
                + "https://evil.invalid/file.ts\n#EXT-X-ENDLIST");
        reject("#EXTM3U\n#EXTINF:5.0,\nsegment0.mp4\n#EXT-X-ENDLIST");
        reject(MEDIA + "stray.ts\n");
    }

    @Test public void refusesEmptyPlaylist() {
        reject("#EXTM3U\n#EXT-X-ENDLIST");
    }
}
