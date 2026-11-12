package com.example.ytdl;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Public X/Twitter post video lookup. No account cookies, private posts or login.
 *
 * Uses the independent public FxTwitter and vxTwitter APIs (not affiliated
 * with X). Only the numeric status ID is sent, never the user's original URL,
 * tracking query parameters, login cookies or credentials.
 */
final class TwitterVideo {
    private static final String PRIMARY_API = "https://api.fxtwitter.com/status/";
    private static final String BACKUP_API = "https://api.vxtwitter.com/Twitter/status/";
    private static final okhttp3.OkHttpClient METADATA_HTTP =
            Net.HTTP.newBuilder().followRedirects(false).followSslRedirects(false).build();
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
            return (isHlsUrl(url) ? "HLS" : "MP4")
                    + (height > 0 ? " · " + height + "p" : "");
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

    static String pageUrlFor(String url, int index) {
        final String id = statusId(url);
        if (id == null || index < 0 || index >= 16)
            throw new IllegalArgumentException("Invalid public post or video index");
        return "https://x.com/i/status/" + id + "?nova_video=" + index;
    }

    static int selectedIndex(String url) throws IOException {
        if (statusId(url) == null) throw new IOException("Invalid X/Twitter post URL");
        final String index = Uri.parse(url).getQueryParameter("nova_video");
        if (index == null) return 0;
        if (index.length() > 2 || !index.matches("[0-9]{1,2}"))
            throw new IOException("Invalid X video selection");
        final int number = Integer.parseInt(index);
        if (number >= 16) throw new IOException("X post video index out of range");
        return number;
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
        // X sometimes appends /video/1 to shared post links. Recognize only
        // this exact, bounded suffix; never accept arbitrary extra paths.
        final boolean videoSuffix = segments.size() == 5
                && "video".equals(segments.get(3))
                && isVideoOrdinal(segments.get(4));
        if ((segments.size() != 3 && !videoSuffix)
                || !"status".equals(segments.get(1))
                || segments.get(0).isEmpty()) return null;
        final String id = segments.get(2);
        if (id.length() < 10 || id.length() > 22) return null;
        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) < '0' || id.charAt(i) > '9') return null;
        }
        return id;
    }

    private static boolean isVideoOrdinal(String ordinal) {
        if (ordinal == null || ordinal.isEmpty() || ordinal.length() > 2
                || ordinal.charAt(0) == '0') return false;
        for (int i = 0; i < ordinal.length(); i++) {
            if (ordinal.charAt(i) < '0' || ordinal.charAt(i) > '9') return false;
        }
        return Integer.parseInt(ordinal) <= 16;
    }

    /**
     * A proxy may send a huge body without Content-Length. Read at most limit+1
     * bytes so an oversized or malicious response cannot exhaust app memory.
     * The caller owns and closes the stream through its enclosing Response.
     */
    static byte[] readBounded(InputStream input, int limit) throws IOException {
        if (input == null || limit <= 0 || limit == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid metadata input or limit");
        }
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                Math.min(limit, 8192));
        final byte[] block = new byte[8192];
        while (buffer.size() <= limit) {
            final int left = limit + 1 - buffer.size();
            final int read = input.read(block, 0, Math.min(block.length, left));
            if (read < 0) break;
            if (read == 0) {
                // Avoid spinning forever on an unusual stream returning zero bytes.
                final int single = input.read();
                if (single < 0) break;
                buffer.write(single);
            } else {
                buffer.write(block, 0, read);
            }
        }
        if (buffer.size() > limit) throw new IOException("Video metadata too large");
        return buffer.toByteArray();
    }

    /** Try two independent, public metadata providers, without exposing share tokens. */
    static Post fetch(String postUrl) throws IOException {
        final String id = statusId(postUrl);
        if (id == null) throw new IOException("Enter a public X/Twitter post URL");
        IOException primaryFailure;
        try {
            return parse(id, fetchJson(PRIMARY_API + id));
        } catch (IOException e) {
            primaryFailure = e;
        }
        try {
            return parseVx(id, fetchJson(BACKUP_API + id));
        } catch (IOException fallbackFailure) {
            final IOException unavailable = new IOException(
                    "Public X video is unavailable from both metadata providers");
            unavailable.addSuppressed(primaryFailure);
            unavailable.addSuppressed(fallbackFailure);
            throw unavailable;
        }
    }

    private static JSONObject fetchJson(String url) throws IOException {
        final Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "NOVADownloader/1.6 (public media metadata)")
                .build();
        try (Response response = METADATA_HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Public metadata provider returned HTTP "
                        + response.code());
            }
            final long length = response.body().contentLength();
            if (length > MAX_JSON_BYTES) throw new IOException("Video metadata too large");
            final byte[] bytes = readBounded(response.body().byteStream(), MAX_JSON_BYTES);
            try {
                return new JSONObject(new String(bytes,
                        java.nio.charset.StandardCharsets.UTF_8));
            } catch (JSONException invalid) {
                throw new IOException("Unexpected public video metadata", invalid);
            }
        }
    }

    /** vxTwitter's documented public response has a separate media_extended shape. */
    static Post parseVx(String expectedId, JSONObject root) throws IOException {
        if (expectedId == null || !expectedId.matches("[0-9]{10,22}")) {
            throw new IOException("Invalid public post ID");
        }
        if (!expectedId.equals(root.optString("tweetID", ""))) {
            throw new IOException("Backup provider post ID mismatch");
        }
        final List<Video> clips = new ArrayList<>();
        final JSONArray extended = root.optJSONArray("media_extended");
        if (extended != null) {
            for (int i = 0; i < extended.length() && clips.size() < 16; i++) {
                final JSONObject item = extended.optJSONObject(i);
                if (item == null) continue;
                final String type = item.optString("type", "");
                if (!"video".equals(type) && !"gif".equals(type)) continue;
                final String url = item.optString("url", "");
                if (!isSafeMp4Url(url) && !isHlsUrl(url)) continue;
                final JSONObject size = item.optJSONObject("size");
                clips.add(new Video(url, size == null ? 0 : Math.max(0, size.optInt("width")),
                        size == null ? 0 : Math.max(0, size.optInt("height")),
                        0, safeThumbnail(item.optString("thumbnail_url", ""))));
            }
        }
        // Older vxTwitter records sometimes return only mediaURLs.
        if (clips.isEmpty()) {
            final JSONArray links = root.optJSONArray("mediaURLs");
            if (links != null) {
                for (int i = 0; i < links.length() && clips.size() < 16; i++) {
                    final String url = links.optString(i, "");
                    if (isSafeMp4Url(url) || isHlsUrl(url)) {
                        clips.add(new Video(url, 0, 0, 0, ""));
                    }
                }
            }
        }
        if (clips.isEmpty()) throw new IOException("No public MP4 or HLS video in backup");
        final String raw = root.optString("text", "").replaceAll("\\s+", " ").trim();
        final String title = raw.isEmpty() ? "X video " + expectedId
                : raw.length() > 80 ? raw.substring(0, 80) : raw;
        return new Post("https://x.com/i/status/" + expectedId,
                title, root.optString("user_name", ""), clips);
    }

    static Post parse(String expectedId, JSONObject root) throws IOException {
        if (expectedId == null || !expectedId.matches("[0-9]{10,22}")) {
            throw new IOException("Invalid post identifier");
        }
        final JSONObject tweet = root.optJSONObject("tweet");
        if (tweet == null || root.optInt("code", -1) != 200) {
            throw new IOException("This public post is unavailable or has no accessible media");
        }
        // Older API responses may omit tweet.id, but when provided it must match.
        final String returnedId = tweet.optString("id", "");
        if (!returnedId.isEmpty() && !expectedId.equals(returnedId)) {
            throw new IOException("Post ID mismatch in public video lookup");
        }
        final JSONObject media = tweet.optJSONObject("media");
        final JSONArray videos = media == null ? null : media.optJSONArray("videos");
        if (videos == null || videos.length() == 0) {
            throw new IOException("No public video found in this post");
        }
        final List<Video> found = new ArrayList<>();
        for (int i = 0; i < videos.length() && found.size() < 16; i++) {
            final JSONObject video = videos.optJSONObject(i);
            if (video == null) continue;
            final JSONArray variants = video.optJSONArray("formats") == null
                    ? video.optJSONArray("variants") : video.optJSONArray("formats");
            final String thumb = safeThumbnail(video.optString("thumbnail_url",
                    video.optString("thumbnail", "")));
            final int width = Math.max(0, video.optInt("width"));
            final int height = Math.max(0, video.optInt("height"));
            Video mp4 = null;
            Video hls = null;
            if (variants != null) {
                for (int j = 0; j < variants.length(); j++) {
                    final JSONObject variant = variants.optJSONObject(j);
                    if (variant == null) continue;
                    final String url = variant.optString("url");
                    final int bitrate = Math.max(0, variant.optInt("bitrate"));
                    if (isSafeMp4Url(url) && (mp4 == null || bitrate > mp4.bitrate)) {
                        mp4 = new Video(url, width, height, bitrate, thumb);
                    } else if (isHlsUrl(url) && (hls == null || bitrate > hls.bitrate)) {
                        hls = new Video(url, width, height, bitrate, thumb);
                    }
                }
            }
            final String direct = video.optString("url", "");
            if (mp4 == null && isSafeMp4Url(direct)) {
                mp4 = new Video(direct, width, height, 0, thumb);
            } else if (hls == null && isHlsUrl(direct)) {
                hls = new Video(direct, width, height, 0, thumb);
            }
            // Prefer the resumable direct MP4. Only use HLS when MP4 is unavailable.
            if (mp4 != null) found.add(mp4);
            else if (hls != null) found.add(hls);
        }
        if (found.isEmpty()) {
            throw new IOException("No public MP4 or supported HLS video in this post");
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
        return isSafeCdnFile(value, ".mp4");
    }

    static boolean isHlsUrl(String value) {
        return isSafeCdnFile(value, ".m3u8");
    }

    static boolean isSafeCdnFile(String value, String suffix) {
        if (value == null || value.isEmpty()) return false;
        try {
            final Uri uri = Uri.parse(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getHost() == null
                    || uri.getFragment() != null
                    || !"video.twimg.com".equalsIgnoreCase(uri.getHost())) return false;
            final String path = uri.getPath();
            return path != null && path.toLowerCase(Locale.US).endsWith(suffix);
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
