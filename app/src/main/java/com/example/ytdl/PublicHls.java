package com.example.ytdl;

import android.content.Context;
import android.os.StatFs;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Bounded public HLS VOD support for ordinary video pages and supported extractors.
 *
 * Only unencrypted, non-live MPEG-TS playlists are accepted. No key URI, DRM,
 * byte-range, fMP4 init map, or FFmpeg network protocol is ever followed.
 * Remote bytes are fetched by OkHttp with public-address DNS validation, then
 * FFmpeg sees only the completed local playlist and local segment files.
 */
final class PublicHls {
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_SEGMENTS = 2048;
    private static final long MAX_SEGMENT_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_TOTAL_DURATION_SECONDS = 4L * 60 * 60;
    private static final long STORAGE_RESERVE_BYTES = 48L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 4;

    private static final OkHttpClient HTTP = Net.HTTP.newBuilder()
            .dns(new PublicDns())
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    static final class Segment {
        final String url;
        final double duration;

        Segment(String url, double duration) {
            this.url = url;
            this.duration = duration;
        }
    }

    static final class Playlist {
        final String url;
        final List<Segment> segments;
        final String fingerprint;
        final int targetDuration;

        Playlist(String url, List<Segment> segments, String fingerprint,
                 int targetDuration) {
            this.url = url;
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
            this.fingerprint = fingerprint;
            this.targetDuration = targetDuration;
        }
    }

    private PublicHls() {}

    static boolean isManifestUrl(String value) {
        if (value == null) return false;
        try {
            final String path = new URI(value).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
        } catch (Exception ignored) {
            return false;
        }
    }

    static List<PublicVideoLinks.Video> variants(String url, String raw) throws IOException {
        final String safe = safeUrl(url);
        if (raw == null || raw.length() > MAX_MANIFEST_BYTES
                || !raw.trim().startsWith("#EXTM3U")) {
            throw new IOException("Invalid or oversized HLS manifest");
        }
        final String[] lines = raw.replace("\r", "").split("\n");
        final List<PublicVideoLinks.Video> choices = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            final String attrs = line.substring("#EXT-X-STREAM-INF:".length());
            // Separate external audio renditions are intentionally not merged here.
            if (attrs.matches("(?is).*\\bAUDIO\\s*=.*")) continue;
            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                final String next = lines[j].trim();
                if (next.isEmpty()) continue;
                if (next.startsWith("#")) break;
                uri = next;
                i = j;
                break;
            }
            if (uri == null) throw new IOException("HLS variant is missing its URL");
            final String variant = resolve(safe, uri);
            final int height = resolutionHeight(attrs);
            final String label = height > 0 ? "HLS · " + height + "p"
                    : "HLS · " + bandwidthLabel(attrs);
            choices.add(new PublicVideoLinks.Video(variant, label, "m3u8",
                    "application/vnd.apple.mpegurl"));
            if (choices.size() >= 12) break;
        }
        if (!choices.isEmpty()) {
            choices.sort((a, b) -> Integer.compare(
                    resolutionHeightFromLabel(b.label), resolutionHeightFromLabel(a.label)));
            return choices;
        }
        // A media playlist is itself one selectable source, but validate its structure now.
        parseMedia(safe, raw);
        return Collections.singletonList(new PublicVideoLinks.Video(
                safe, "HLS · public VOD", "m3u8", "application/vnd.apple.mpegurl"));
    }

    static List<PublicVideoLinks.Video> variants(String url) throws IOException {
        final String safe = safeUrl(url);
        return variants(safe, fetchManifest(safe));
    }

    static Playlist parseMedia(String url, String raw) throws IOException {
        final String safe = safeUrl(url);
        if (raw == null || raw.length() > MAX_MANIFEST_BYTES
                || !raw.trim().startsWith("#EXTM3U")) {
            throw new IOException("Invalid or oversized HLS media playlist");
        }
        if (!raw.contains("#EXT-X-ENDLIST")) {
            throw new IOException("Live HLS playlists are not supported");
        }

        final List<Segment> segments = new ArrayList<>();
        final String[] lines = raw.replace("\r", "").split("\n");
        double pendingDuration = -1;
        double totalDuration = 0;
        boolean ended = false;
        for (String original : lines) {
            final String line = original.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-KEY")
                    || line.startsWith("#EXT-X-SESSION-KEY")
                    || line.startsWith("#EXT-X-MAP")
                    || line.startsWith("#EXT-X-BYTERANGE")
                    || line.startsWith("#EXT-X-DISCONTINUITY")
                    || line.startsWith("#EXT-X-I-FRAMES-ONLY")
                    || line.startsWith("#EXT-X-PART")
                    || line.startsWith("#EXT-X-GAP")
                    || line.startsWith("#EXT-X-DATERANGE")) {
                throw new IOException("Encrypted, ranged or fragmented HLS is not supported");
            }
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                throw new IOException("Expected an HLS media playlist, received a master");
            }
            if (line.startsWith("#EXT-X-ENDLIST")) {
                ended = true;
                continue;
            }
            if (ended && !line.startsWith("#")) {
                throw new IOException("HLS content appears after ENDLIST");
            }
            if (line.startsWith("#EXTINF:")) {
                if (pendingDuration > 0) throw new IOException("Duplicate HLS segment duration");
                final String duration = line.substring("#EXTINF:".length()).split(",", 2)[0];
                try {
                    pendingDuration = Double.parseDouble(duration);
                } catch (NumberFormatException invalid) {
                    throw new IOException("Invalid HLS segment duration");
                }
                if (!Double.isFinite(pendingDuration) || pendingDuration <= 0
                        || pendingDuration > 120) {
                    throw new IOException("HLS segment duration is outside the supported range");
                }
            } else if (line.startsWith("#")) {
                continue;
            } else {
                if (pendingDuration <= 0) {
                    throw new IOException("HLS segment has no duration");
                }
                final String segment = resolve(safe, line);
                if (isManifestUrl(segment)) {
                    throw new IOException("Nested HLS manifest found in media playlist");
                }
                segments.add(new Segment(segment, pendingDuration));
                totalDuration += pendingDuration;
                pendingDuration = -1;
                if (segments.size() > MAX_SEGMENTS
                        || totalDuration > MAX_TOTAL_DURATION_SECONDS) {
                    throw new IOException("HLS playlist exceeds the supported duration or segment limit");
                }
            }
        }
        if (!ended || pendingDuration > 0 || segments.isEmpty()) {
            throw new IOException("Incomplete HLS VOD playlist");
        }
        final int target = Math.max(1, (int) Math.ceil(
                segments.stream().mapToDouble(x -> x.duration).max().orElse(1)));
        return new Playlist(safe, segments, sha256(safe + "\n" + raw), target);
    }

    static File download(Context context, long id, String manifestUrl,
                         Net.Progress progress, Net.Control control) throws IOException {
        control.checkpoint();
        String selectedUrl = safeUrl(manifestUrl);
        String raw = fetchManifest(selectedUrl);
        final List<PublicVideoLinks.Video> master = variantsOnly(selectedUrl, raw);
        if (!master.isEmpty()) {
            // If an extractor supplied a master manifest directly, choose its highest
            // non-external-audio rendition. Page previews expose individual variants.
            selectedUrl = master.get(0).url;
            raw = fetchManifest(selectedUrl);
        }
        final Playlist playlist = parseMedia(selectedUrl, raw);
        final File dir = directory(context, id);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create public HLS cache");
        }
        final File fingerprint = new File(dir, "fingerprint");
        final String old = fingerprint.isFile()
                ? new String(Files.readAllBytes(fingerprint.toPath()), StandardCharsets.US_ASCII)
                : "";
        if (!playlist.fingerprint.equals(old)) {
            clearFiles(dir);
            Files.write(fingerprint.toPath(),
                    playlist.fingerprint.getBytes(StandardCharsets.US_ASCII));
        }

        long total = 0;
        long done = 0;
        final StringBuilder local = new StringBuilder("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-PLAYLIST-TYPE:VOD\n")
                .append("#EXT-X-TARGETDURATION:").append(playlist.targetDuration).append('\n')
                .append("#EXT-X-MEDIA-SEQUENCE:0\n");
        for (int i = 0; i < playlist.segments.size(); i++) {
            control.checkpoint();
            final Segment item = playlist.segments.get(i);
            final File segment = new File(dir, String.format(Locale.US, "%04d.ts", i));
            if (!segment.isFile() || segment.length() <= 0) {
                downloadSegment(item.url, segment, control);
            }
            total += segment.length();
            if (total > MAX_TOTAL_BYTES) throw new IOException("HLS video exceeds size limit");
            done += segment.length();
            progress.onProgress(done, -1);
            local.append("#EXTINF:")
                    .append(String.format(Locale.US, "%.3f", item.duration))
                    .append(",\n").append(segment.getName()).append('\n');
        }
        local.append("#EXT-X-ENDLIST\n");
        final File manifest = new File(dir, "local.m3u8");
        Files.write(manifest.toPath(), local.toString().getBytes(StandardCharsets.UTF_8));
        return manifest;
    }

    static File remux(Context context, long id, File manifest,
                      Net.Control control) throws IOException {
        control.checkpoint();
        final File output = new File(context.getCacheDir(), "public-hls-" + id + ".mp4");
        long cachedBytes = 0;
        final File dir = directory(context, id);
        final File[] segments = dir.listFiles();
        if (segments != null) {
            for (File segment : segments) {
                if (segment.isFile() && segment.getName().endsWith(".ts")) {
                    cachedBytes += segment.length();
                }
            }
        }
        final long available = dir.getUsableSpace();
        if (available > 0 && available < cachedBytes + STORAGE_RESERVE_BYTES) {
            throw new IOException("Not enough free storage to remux the completed HLS video");
        }
        if (output.exists() && !output.delete()) {
            throw new IOException("Cannot replace previous HLS output");
        }
        // FFmpeg only receives local files. It cannot make remote network requests.
        final String command = "-y -protocol_whitelist file -allowed_extensions ALL -i "
                + quote(manifest.getAbsolutePath())
                + " -map 0:v:0 -map 0:a:0? -c copy -movflags +faststart "
                + quote(output.getAbsolutePath());
        final FFmpegSession session = FFmpegKit.execute(command);
        control.checkpoint();
        if (!ReturnCode.isSuccess(session.getReturnCode())
                || !output.isFile() || output.length() == 0) {
            output.delete();
            throw new IOException("Could not remux public HLS video");
        }
        return output;
    }

    static File directory(Context context, long id) {
        return new File(context.getCacheDir(), "public-hls-" + id);
    }

    static void removeDirectory(Context context, long id) {
        final File dir = directory(context, id);
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) file.delete();
            }
        }
        dir.delete();
        new File(context.getCacheDir(), "public-hls-" + id + ".mp4").delete();
    }

    private static List<PublicVideoLinks.Video> variantsOnly(String url, String raw)
            throws IOException {
        final String[] lines = raw.replace("\r", "").split("\n");
        final List<PublicVideoLinks.Video> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            final String attrs = line.substring("#EXT-X-STREAM-INF:".length());
            if (attrs.matches("(?is).*\\bAUDIO\\s*=.*")) continue;
            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                final String next = lines[j].trim();
                if (next.isEmpty()) continue;
                if (next.startsWith("#")) break;
                uri = next;
                i = j;
                break;
            }
            if (uri == null) throw new IOException("HLS variant is missing its URL");
            final String target = resolve(url, uri);
            out.add(new PublicVideoLinks.Video(target,
                    "HLS · " + (resolutionHeight(attrs) > 0
                            ? resolutionHeight(attrs) + "p" : bandwidthLabel(attrs)),
                    "m3u8", "application/vnd.apple.mpegurl"));
            if (out.size() >= 12) break;
        }
        out.sort((a, b) -> Integer.compare(
                resolutionHeightFromLabel(b.label), resolutionHeightFromLabel(a.label)));
        return out;
    }

    private static String fetchManifest(String url) throws IOException {
        final Response response = execute(url, true);
        try (Response close = response) {
            if (!close.isSuccessful() || close.body() == null) {
                throw new IOException("HLS manifest HTTP " + close.code());
            }
            if (close.body().contentLength() > MAX_MANIFEST_BYTES) {
                throw new IOException("HLS manifest is too large");
            }
            final byte[] bytes = close.body().source().readByteArray(MAX_MANIFEST_BYTES + 1L);
            if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("HLS manifest is too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void downloadSegment(String url, File target,
                                        Net.Control control) throws IOException {
        final File part = new File(target.getParentFile(), target.getName() + ".tmp");
        if (part.exists() && !part.delete()) {
            throw new IOException("Cannot reset incomplete HLS segment");
        }
        try (Response response = execute(url, false)) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HLS segment HTTP " + response.code());
            }
            if (response.body().contentLength() > MAX_SEGMENT_BYTES) {
                throw new IOException("HLS segment is too large");
            }
            final String encoding = response.header("Content-Encoding");
            if (encoding != null && !"identity".equalsIgnoreCase(encoding.trim())) {
                throw new IOException("Compressed HLS segment is not supported");
            }
            long written = 0;
            final byte[] buffer = new byte[64 * 1024];
            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    control.checkpoint();
                    written += count;
                    if (written > MAX_SEGMENT_BYTES) {
                        throw new IOException("HLS segment exceeds size limit");
                    }
                    ensureSpace(part, count);
                    out.write(buffer, 0, count);
                }
            }
            if (written == 0) throw new IOException("Empty HLS segment");
            if (target.exists() && !target.delete()) {
                throw new IOException("Cannot replace HLS segment");
            }
            if (!part.renameTo(target)) throw new IOException("Cannot finalize HLS segment");
        } finally {
            part.delete();
        }
    }

    private static Response execute(String url, boolean manifest) throws IOException {
        String current = safeUrl(url);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            final Request request = new Request.Builder().url(current)
                    .header("User-Agent", Net.USER_AGENT)
                    .header("Accept-Encoding", "identity")
                    .build();
            final Response response = HTTP.newCall(request).execute();
            final int code = response.code();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                final String location = response.header("Location");
                response.close();
                if (location == null) throw new IOException("HLS redirect has no location");
                current = resolve(current, location);
            } else {
                return response;
            }
        }
        throw new IOException("Too many HLS redirects");
    }

    private static String resolve(String base, String relative) throws IOException {
        try {
            final URI resolved = new URI(base).resolve(relative.trim());
            return safeUrl(resolved.toString());
        } catch (Exception invalid) {
            if (invalid instanceof IOException) throw (IOException) invalid;
            throw new IOException("Invalid HLS resource URL", invalid);
        }
    }

    private static String safeUrl(String input) throws IOException {
        try {
            final URI uri = new URI(input);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getHost().length() > 253) {
                throw new IOException("HLS resources must use public HTTPS URLs");
            }
            final String host = uri.getHost().toLowerCase(Locale.ROOT);
            if ("localhost".equals(host) || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal")) {
                throw new IOException("Local HLS resources are not supported");
            }
            return uri.toASCIIString();
        } catch (java.net.URISyntaxException invalid) {
            throw new IOException("Invalid HLS URL", invalid);
        }
    }

    private static void ensureSpace(File file, long incoming) throws IOException {
        final long available = new StatFs(file.getParentFile().getAbsolutePath())
                .getAvailableBytes();
        if (available < incoming + STORAGE_RESERVE_BYTES) {
            throw new IOException("Not enough storage for HLS video");
        }
    }

    private static void clearFiles(File dir) throws IOException {
        final File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && !file.delete()) {
                throw new IOException("Cannot clear stale HLS cache");
            }
        }
    }

    private static int resolutionHeight(String attrs) {
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)RESOLUTION=\\d+x(\\d+)").matcher(attrs);
        if (!m.find()) return 0;
        try { return Integer.parseInt(m.group(1)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static int resolutionHeightFromLabel(String label) {
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)p").matcher(label);
        if (!m.find()) return 0;
        try { return Integer.parseInt(m.group(1)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String bandwidthLabel(String attrs) {
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)BANDWIDTH=(\\d+)").matcher(attrs);
        if (!m.find()) return "quality unknown";
        try { return Math.max(1, Integer.parseInt(m.group(1)) / 1000) + " kbps"; }
        catch (NumberFormatException ignored) { return "quality unknown"; }
    }

    private static String sha256(String value) throws IOException {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(Locale.US, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class PublicDns implements Dns {
        @Override public List<InetAddress> lookup(String hostname)
                throws java.net.UnknownHostException {
            final List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            for (InetAddress address : addresses) {
                if (!PublicVideoLinks.isPublicAddress(address)) {
                    throw new java.net.UnknownHostException("Non-public HLS destination");
                }
            }
            return addresses;
        }
    }
}
