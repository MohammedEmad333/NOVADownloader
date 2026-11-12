package com.example.ytdl;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class TwitterVideoTest {
    private static final String ID = "1548602399862013953";
    private static final String URL = "https://video.twimg.com/ext_tw_video/1/pu/vid/720x1280/abc.mp4?tag=14";

    private static JSONObject response() throws Exception {
        final JSONObject format = new JSONObject()
                .put("container", "mp4")
                .put("bitrate", 1800000)
                .put("url", URL);
        final JSONObject hls = new JSONObject().put("container", "m3u8")
                .put("bitrate", 3200000)
                .put("url", "https://video.twimg.com/ext_tw_video/1/pu/pl/index.m3u8");
        final JSONObject video = new JSONObject()
                .put("width", 720).put("height", 1280)
                .put("thumbnail_url", "https://pbs.twimg.com/media/thumb.jpg")
                .put("formats", new JSONArray().put(hls).put(format));
        return new JSONObject().put("code", 200)
                .put("tweet", new JSONObject().put("id", ID)
                        .put("text", "My public video")
                        .put("author", new JSONObject().put("name", "A creator"))
                        .put("media", new JSONObject().put("videos", new JSONArray().put(video))));
    }

    @Test public void recognizesPublicStatusUrlsOnly() {
        assertEquals(ID, TwitterVideo.statusId("https://x.com/person/status/" + ID + "?s=20"));
        assertEquals(ID, TwitterVideo.statusId("https://twitter.com/person/status/" + ID));
        assertEquals(ID, TwitterVideo.statusId("https://mobile.twitter.com/person/status/" + ID));
        assertFalse(TwitterVideo.isPostUrl("http://x.com/person/status/" + ID));
        assertFalse(TwitterVideo.isPostUrl("https://x.com.bad.example/person/status/" + ID));
        assertFalse(TwitterVideo.isPostUrl("https://x.com@evil.example/person/status/" + ID));
        assertFalse(TwitterVideo.isPostUrl("https://x.com/person"));
        assertFalse(TwitterVideo.isPostUrl("https://x.com/person/status/not-a-number"));
    }

    @Test public void choosesPublicMp4AndIgnoresHls() throws Exception {
        final TwitterVideo.Post post = TwitterVideo.parse(ID, response());
        assertEquals("My public video", post.title);
        assertEquals("A creator", post.author);
        assertEquals(1, post.videos.size());
        assertEquals(URL, post.videos.get(0).url);
        assertEquals(1280, post.videos.get(0).height);
        assertEquals(1800000, post.videos.get(0).bitrate);
    }

    @Test public void handlesDirectVideoUrlWithoutFormats() throws Exception {
        final JSONObject root = response();
        final JSONObject video = root.getJSONObject("tweet").getJSONObject("media")
                .getJSONArray("videos").getJSONObject(0);
        video.remove("formats");
        video.put("url", URL);
        assertEquals(URL, TwitterVideo.parse(ID, root).videos.get(0).url);
    }

    @Test public void selectsHighestBitrateMp4Variant() throws Exception {
        final JSONObject root = response();
        final JSONObject video = root.getJSONObject("tweet").getJSONObject("media")
                .getJSONArray("videos").getJSONObject(0);
        video.getJSONArray("formats").put(new JSONObject()
                .put("container", "mp4")
                .put("bitrate", 2500000)
                .put("url", "https://video.twimg.com/another.mp4"));
        assertEquals(2500000, TwitterVideo.parse(ID, root).videos.get(0).bitrate);
    }

    @Test public void rejectsInvalidMediaHostsAndMissingVideos() throws Exception {
        assertFalse(TwitterVideo.isSafeMp4Url("https://video.twimg.com.evil.example/video.mp4"));
        assertFalse(TwitterVideo.isSafeMp4Url("http://video.twimg.com/video.mp4"));
        assertFalse(TwitterVideo.isSafeMp4Url("https://video.twimg.com/video.m3u8"));
        assertTrue(TwitterVideo.isSafeMp4Url(URL));
        final JSONObject root = response();
        root.getJSONObject("tweet").getJSONObject("media")
                .put("videos", new JSONArray());
        try {
            TwitterVideo.parse(ID, root);
            fail("Expected public video error");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("No public video"));
        }
    }

    @Test public void rejectsMismatchedPostAndNonSuccessCode() throws Exception {
        final JSONObject root = response();
        root.getJSONObject("tweet").put("id", "1234567890123456789");
        try {
            TwitterVideo.parse(ID, root);
            fail("Wrong post allowed");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Post ID mismatch"));
        }
        root.put("code", 404);
        try {
            TwitterVideo.parse(ID, root);
            fail("Unavailable post allowed");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("unavailable"));
        }
    }

    @Test public void persistsClipChoiceInCanonicalPageUrl() throws Exception {
        final String url = TwitterVideo.pageUrlFor("https://twitter.com/person/status/" + ID, 2);
        assertEquals("https://x.com/i/status/" + ID + "?nova_video=2", url);
        assertEquals(ID, TwitterVideo.statusId(url));
        assertEquals(2, TwitterVideo.selectedIndex(url));
        assertEquals(0, TwitterVideo.selectedIndex("https://x.com/a/status/" + ID));
        try {
            TwitterVideo.selectedIndex("https://x.com/i/status/" + ID + "?nova_video=abc");
            fail("Invalid clip index accepted");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Invalid"));
        }
    }

    @Test public void linkParserAcceptsSharedXPostInsideText() {
        final String link = "https://x.com/person/status/" + ID;
        assertEquals(link, LinkParser.firstSupportedMediaLink("Video: " + link + "."));
    }
}
