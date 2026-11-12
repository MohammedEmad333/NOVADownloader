package com.example.ytdl;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Public X/Twitter post video lookup. No account cookies, private posts or login.
 *
 * Uses the independent public FxTwitter API (not affiliated with X). A user
 * supplies a public post URL; its numeric status ID is sent to api.fxtwitter.com.
 * Never send the original URL, its query parameters, or any credentials.
 */
final class TwitterVideo {
    private static final String API = "https://api.fxtwitter.com/status/";
    private static final int MAX_JSON_BYTES = 512 * 1024;

    static final class Video {
        final String url;
        final int width;
        final int height;
        final int bitrate;
        final String thumbnail;

        Video(String url, int width, int height, int bitrate, String thumbnail) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.bitrate = bitrate;
            this.thumbnail = thumbnail;
        }

        String label() {
            return height > 0 ? height + "p" : "MP4";
        }
    }

    static final class Post {
        final String canonicalUrl;
        final String title;
        final String author;
        final List<Video> videos;

        Post(String canonicalUrl, String title, String author, List<Video> videos) {
            this.canonicalUrl = canonicalUrl;
            this.title = title;
            this.author = author;
            this.videos = Collections.unmodifiableList(new ArrayList<>(videos));
        }
    }

    private TwitterVideo() {}

    static boolean isPostUrl(String value) {
        return statusId(value) != null;
    }

    static String statusId(String value) {
        if (value == null) return null;
        final Uri uri;
        try { uri = Uri.parse(value.trim()); }
        catch (RuntimeException invalid) { return null; }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getPort() != -1) return null;
        final String host = uri.getHost();
        if (host == null) return null;
        final String normalized = host.toLowerCase(Locale.US);
        if (!normalized.equals("x.com") && !normalized.equals("www.x.com")
                && !normalized.equals("twitter.com") && !normalized.equals("www.twitter.com")
                && !normalized.equals("mobile.twitter.com")) return null;
        final List<String> segments = uri.getPathSegments();
        if (segments.size() != 3 || !"status".equals(segments.get(1))
                || segments.get(0).isEmpty()) return null;
        final String id = segments.get(2);
        if (id.length() < 10 || id.length() > 22) return null;
        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) < '0' || id.charAt(i) > '9') return null;
        }
        return id;
    }

    static Post fetch(String postUrl) throws IOException {
        final String id = statusId(postUrl);
        if (id == null) throw new IOException("Enter a public X/Twitter post URL");
        final Request request = new Request.Builder().url(API + id)
                .header("Accept", "application/json").build();
        try (Response response = Net.HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Public X video lookup unavailable (HTTP "
                        + response.code() + ")");
            }
            final long length = response.body().contentLength();
            if (length > MAX_JSON_BYTES) throw new IOException("Video metadata too large");
            final byte[] bytes = response.body().bytes();
            if (bytes.length > MAX_JSON_BYTES) throw new IOException("Video metadata too large");
            try {
                return parse(id, new JSONObject(new String(bytes,
                        java.nio.charset.StandardCharsets.UTF_8)));
            } catch (JSONException invalid) {
                throw new IOException("Unexpected public X video metadata", invalid);
            }
        }
    }

    static Post parse(String expectedId, JSONObject root) throws IOException {
        if (expectedId == null || !expectedId.matches("[0-9]{10,22}")) {
            throw new IOException("Invalid post identifier");
        }
        final JSONObject tweet = root.optJSONObject("tweet");
        if (tweet == null || !"ok".equalsIgnoreCase(root.optString("code", ""))) {
            throw new IOException("This public post is unavailable or has no accessible media");
        }
        if (!expectedId.equals(tweet.optString("id"))) {
            throw new IOException("Post ID mismatch in public video lookup");
        }
        final JSONObject media = tweet.optJSONObject("media");
        final JSONArray videos = media == null ? null : media.optJSONArray("videos");
        if (videos == null || videos.length() == 0) {
            throw new IOException("No public video found in this post");
        }
        final List<Video> found = new ArrayList<>();
        for (int i = 0; i < videos.length(); i++) {
            final JSONObject video = videos.optJSONObject(i);
            if (video == null) continue;
            final JSONArray variants = video.optJSONArray("variants");
            final String thumb = safeThumbnail(video.optString("thumbnail_url",
                    video.optString("thumbnail", "")));
            final int width = Math.max(0, video.optInt("width"));
            final int height = Math.max(0, video.optInt("height"));
            Video best = null;
            if (variants != null) {
                for (int j = 0; j < variants.length(); j++) {
                    final JSONObject variant = variants.optJSONObject(j);
                    if (variant == null) continue;
                    final String url = variant.optString("url");
                    if (!isSafeMp4Url(url)) continue;
                    final int bitrate = Math.max(0, variant.optInt("bitrate"));
                    if (best == null || bitrate > best.bitrate) {
                        best = new Video(url, width, height, bitrate, thumb);
                    }
                }
            }
            // Some FxTwitter responses expose a direct video URL without variants.
            if (best == null && isSafeMp4Url(video.optString("url"))) {
                best = new Video(video.optString("url"), width, height, 0, thumb);
            }
            if (best != null) found.add(best);
        }
        if (found.isEmpty()) {
            throw new IOException("No downloadable public MP4 variant found in this post");
        }
        final JSONObject author = tweet.optJSONObject("author");
        final String authorName = author == null ? "" : author.optString("name", "");
        final String text = tweet.optString("text", "").replaceAll("\\s+", " ").trim();
        final String title = text.isEmpty() ? "X video " + expectedId
                : text.length() > 80 ? text.substring(0, 80) : text;
        return new Post("https://x.com/i/status/" + expectedId,
                title, authorName, found);
    }

    static boolean isSafeMp4Url(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            final Uri uri = Uri.parse(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getHost() == null
                    || !"video.twimg.com".equalsIgnoreCase(uri.getHost())) return false;
            final String path = uri.getPath();
            return path != null && path.toLowerCase(Locale.US).endsWith(".mp4");
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String safeThumbnail(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            final Uri uri = Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && ("pbs.twimg.com".equalsIgnoreCase(uri.getHost())
                    || "video.twimg.com".equalsIgnoreCase(uri.getHost()))
                    ? value : "";
        } catch (RuntimeException invalid) {
            return "";
        }
    }
}
