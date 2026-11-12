package com.example.ytdl;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Narrow, bounded HLS transport-stream support for publicly available X videos.
 * Uses only video.twimg.com; no external playlists, redirects, DRM keys,
 * byte ranges, live streams, or FFmpeg network protocols.
 * Each complete segment is atomically committed to enable pause/resume.
 */
final class TwitterHls {
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_SEGMENTS = 512;
    private static final long MAX_SEGMENT_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    private static final long STORAGE_RESERVE_BYTES = 32L * 1024 * 1024;
    private static final OkHttpClient HTTP = Net.HTTP.newBuilder()
            .followRedirects(false).followSslRedirects(false).build();

    static final class Segment {
        final String url;
        Segment(String url) { this.url = url; }
    }

    static final class Playlist {
        final String url;
        final List<Segment> segments;
        final String fingerprint;

        Playlist(String url, List<Segment> segments, String fingerprint) {
            this.url = url;
            this.segments = segments;
            this.fingerprint = fingerprint;
        }
    }

    private TwitterHls() {}

    static File directory(Context context, long id) {
        return new File(context.getCacheDir(), "hls-" + id);
    }

    static void removeDirectory(Context context, long id) {
        final File directory = directory(context, id);
        final File[] items = directory.listFiles();
        if (items != null) {
            for (File item : items) {
                if (item.isFile() && !Files.isSymbolicLink(item.toPath())) item.delete();
            }
        }
        directory.delete();
    }

    static boolean safeSegment(String value) {
        return TwitterVideo.isSafeCdnFile(value, ".ts");
    }

    static String resolve(String base, String relative) throws IOException {
        if (relative == null || relative.trim().isEmpty()
                || relative.indexOf('\r') >= 0 || relative.indexOf('\n') >= 0) {
            throw new IOException("Invalid HLS resource path");
        }
        final Uri target = Uri.parse(base).buildUpon().build();
        final java.net.URI resolved;
        try {
            resolved = new java.net.URI(target.toString()).resolve(relative.trim());
        } catch (java.net.URISyntaxException invalid) {
            throw new IOException("Malformed HLS resource", invalid);
        }
        final String url = resolved.toString();
        if (!TwitterVideo.isHlsUrl(url) && !safeSegment(url)) {
            throw new IOException("HLS resource outside supported public CDN");
        }
        return url;
    }

    static String selectVariant(String base, String raw) throws IOException {
        final String[] lines = raw.replace("\r", "").split("\n");
        long best = -1;
        String result = null;
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            final java.util.regex.Matcher bandwidth = java.util.regex.Pattern
                    .compile("(?:^|,)BANDWIDTH=([0-9]+)")
                    .matcher(line.substring("#EXT-X-STREAM-INF:".length()));
            long bps = 0;
            if (bandwidth.find()) {
                try { bps = Long.parseLong(bandwidth.group(1)); }
                catch (NumberFormatException ignored) { bps = 0; }
            }
            if (i + 1 >= lines.length) throw new IOException("HLS variant missing URI");
            final String url = resolve(base, lines[++i].trim());
            if (!TwitterVideo.isHlsUrl(url)) throw new IOException("HLS variant not a playlist");
            if (bps > best) {
                best = bps;
                result = url;
            }
        }
        return result;
    }

    static Playlist parseMedia(String url, String raw) throws IOException {
        if (!TwitterVideo.isHlsUrl(url)) throw new IOException("Unsupported HLS host");
        if (raw == null || raw.length() > MAX_MANIFEST_BYTES || !raw.trim().startsWith("#EXTM3U")) {
            throw new IOException("Invalid or oversized HLS manifest");
        }
        if (!raw.contains("#EXT-X-ENDLIST")) {
            throw new IOException("Live and incomplete HLS playlists are not supported");
        }
        final List<Segment> segments = new ArrayList<>();
        final String[] lines = raw.replace("\r", "").split("\n");
        boolean durationSeen = false;
        boolean ended = false;
        for (String original : lines) {
            final String line = original.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY")
                    || line.startsWith("#EXT-X-MAP") || line.startsWith("#EXT-X-BYTERANGE")
                    || line.startsWith("#EXT-X-DISCONTINUITY")
                    || line.startsWith("#EXT-X-I-FRAMES-ONLY")
                    || line.startsWith("#EXT-X-PART")
                    || line.startsWith("#EXT-X-GAP")) {
                throw new IOException("Encrypted, ranged, fragmented or discontinuous HLS is not supported");
            }
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                throw new IOException("Expected HLS media playlist, received master");
            }
            if (line.startsWith("#EXT-X-ENDLIST")) {
                ended = true;
                continue;
            }
            if (ended && !line.startsWith("#")) throw new IOException("HLS content after end");
            if (line.startsWith("#EXTINF:")) {
                if (durationSeen) throw new IOException("Duplicate HLS segment duration");
                durationSeen = true;
            } else if (line.startsWith("#")) {
                continue;
            } else {
                if (!durationSeen) throw new IOException("HLS segment missing duration");
                final String segment = resolve(url, line);
                if (!safeSegment(segment)) throw new IOException("Unsupported HLS segment");
                if (segments.size() >= MAX_SEGMENTS) throw new IOException("Too many HLS segments");
                segments.add(new Segment(segment));
                durationSeen = false;
            }
        }
        if (durationSeen || !ended || segments.isEmpty()) {
            throw new IOException("Incomplete HLS media playlist");
        }
        return new Playlist(url, segments, sha256(url + "\n" + raw));
    }

    static Playlist fetchPlaylist(String url) throws IOException {
        if (!TwitterVideo.isHlsUrl(url)) throw new IOException("Only public X HLS is supported");
        final String initial = fetchManifest(url);
        final String variant = selectVariant(url, initial);
        if (variant != null) {
            return parseMedia(variant, fetchManifest(variant));
        }
        return parseMedia(url, initial);
    }

    private static String fetchManifest(String url) throws IOException {
        final Request request = new Request.Builder().url(url)
                .header("User-Agent", Net.USER_AGENT).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HLS playlist HTTP " + response.code());
            }
            if (response.body().contentLength() > MAX_MANIFEST_BYTES) {
                throw new IOException("HLS playlist too large");
            }
            return new String(TwitterVideo.readBounded(
                    response.body().byteStream(), MAX_MANIFEST_BYTES), StandardCharsets.UTF_8);
        }
    }

    static File download(Context context, long id, String url, Net.Control checkpoint)
            throws IOException {
        checkpoint.checkpoint();
        final Playlist playlist = fetchPlaylist(url);
        final File dir = directory(context, id);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create HLS cache");
        final File fingerprint = new File(dir, "fingerprint");
        final String old = fingerprint.isFile()
                ? new String(Files.readAllBytes(fingerprint.toPath()), StandardCharsets.US_ASCII)
                : "";
        if (!playlist.fingerprint.equals(old)) {
            clearFiles(dir);
            Files.write(fingerprint.toPath(), playlist.fingerprint.getBytes(StandardCharsets.US_ASCII));
        }

        long total = 0;
        final StringBuilder local = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
        for (int i = 0; i < playlist.segments.size(); i++) {
            checkpoint.checkpoint();
            final File segment = new File(dir, String.format(Locale.US, "%04d.ts", i));
            if (!segment.isFile() || segment.length() <= 0) {
                downloadSegment(playlist.segments.get(i).url, segment, checkpoint);
            }
            total += segment.length();
            if (total > MAX_TOTAL_BYTES) throw new IOException("HLS video exceeds size limit");
            local.append("#EXTINF:1.0,\n").append(segment.getName()).append('\n');
        }
        local.append("#EXT-X-ENDLIST\n");
        final File manifest = new File(dir, "local.m3u8");
        Files.write(manifest.toPath(), local.toString().getBytes(StandardCharsets.UTF_8));
        return manifest;
    }

    static File remux(Context context, long id, File manifest, Net.Control checkpoint)
            throws IOException {
        checkpoint.checkpoint();
        final File output = new File(context.getCacheDir(), "m-" + id + ".mp4");
        // File-only protocol: FFmpeg never fetches remote segments or playlist URLs.
        final String cmd = "-y -protocol_whitelist file -allowed_extensions ALL -i "
                + quote(manifest.getAbsolutePath())
                + " -map 0:v:0 -map 0:a:0? -c copy -movflags +faststart "
                + quote(output.getAbsolutePath());
        final FFmpegSession session = FFmpegKit.execute(cmd);
        checkpoint.checkpoint();
        if (!ReturnCode.isSuccess(session.getReturnCode())
                || !output.isFile() || output.length() == 0) {
            throw new IOException("Could not convert HLS segments to MP4");
        }
        return output;
    }

    private static void downloadSegment(String url, File target, Net.Control checkpoint)
            throws IOException {
        if (!safeSegment(url)) throw new IOException("Unsafe X HLS segment");
        final File part = new File(target.getParentFile(), target.getName() + ".tmp");
        if (part.exists() && !part.delete()) throw new IOException("Cannot reset incomplete segment");
        final Request request = new Request.Builder().url(url)
                .header("User-Agent", Net.USER_AGENT).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HLS segment HTTP " + response.code());
            }
            if (response.body().contentLength() > MAX_SEGMENT_BYTES) {
                throw new IOException("HLS segment too large");
            }
            final byte[] buffer = new byte[64 * 1024];
            long written = 0;
            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    checkpoint.checkpoint();
                    written += read;
                    if (written > MAX_SEGMENT_BYTES) throw new IOException("HLS segment too large");
                    ensureSpace(part, read);
                    out.write(buffer, 0, read);
                }
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("Cannot replace invalid HLS segment");
            }
            if (written == 0 || !part.renameTo(target)) {
                throw new IOException("Could not finalize HLS segment");
            }
        } finally {
            // A paused or failed partial segment is never treated as complete on resume.
            part.delete();
        }
    }

    private static void ensureSpace(File file, long incoming) throws IOException {
        final long available = new StatFs(file.getParentFile().getAbsolutePath())
                .getAvailableBytes();
        if (available < incoming + STORAGE_RESERVE_BYTES) {
            throw new IOException("Not enough storage space for HLS");
        }
    }

    private static void clearFiles(File dir) throws IOException {
        final File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isFile() || Files.isSymbolicLink(file.toPath())
                    || !file.delete()) throw new IOException("Cannot reset HLS cache");
        }
    }

    private static String sha256(String data) throws IOException {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8));
            final StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IOException("HLS fingerprint unavailable", unavailable);
        }
    }

    private static String quote(String data) {
        return "'" + data.replace("'", "") + "'";
    }
}
