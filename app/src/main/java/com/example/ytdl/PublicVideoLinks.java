package com.example.ytdl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Discovers downloadable public HTML5 video sources and direct media files.
 *
 * This intentionally does not execute scripts, log in, bypass DRM or scrape
 * private media. All requests use a bounded response and a DNS policy that
 * rejects loopback, local and reserved/private destination addresses.
 */
final class PublicVideoLinks {
    private static final int MAX_HTML_BYTES = 256 * 1024;
    private static final int MAX_SOURCES = 12;
    private static final int MAX_REDIRECTS = 4;
    private static final Pattern TAG = Pattern.compile(
            "<(meta|video|source)\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z_:][\\w:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern TITLE = Pattern.compile(
            "<title\\b[^>]*>(.*?)</title\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final OkHttpClient HTTP = Net.HTTP.newBuilder()
            .dns(new PublicDns()).followRedirects(false)
            .followSslRedirects(false).build();

    // The download pipeline is range-based. Every connection, including redirect
    // destinations, resolves through the same public-only DNS policy.
    static final OkHttpClient DOWNLOAD_HTTP = Net.HTTP.newBuilder()
            .dns(new PublicDns()).build();

    static final class Video {
        final String url;
        final String label;
        final String extension;
        final String mime;

        Video(String url, String label, String extension, String mime) {
            this.url = url;
            this.label = label;
            this.extension = extension;
            this.mime = mime;
        }
    }

    static final class Result {
        final String title;
        final List<Video> videos;

        Result(String title, List<Video> videos) {
            this.title = title;
            this.videos = Collections.unmodifiableList(new ArrayList<>(videos));
        }
    }

    private PublicVideoLinks() {}

    static boolean isWebUrl(String input) {
        try {
            final URI uri = new URI(input);
            final String scheme = uri.getScheme();
            return scheme != null
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty()
                    && uri.getRawUserInfo() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Recognize hosts handled by the existing source-specific extractors first. */
    static boolean isGenericLink(String input) {
        if (!isWebUrl(input) || Spotify.isSpotifyUrl(input) || TwitterVideo.isPostUrl(input)) {
            return false;
        }
        try {
            if (org.schabi.newpipe.extractor.ServiceList.YouTube.getLinkTypeByUrl(input)
                    != org.schabi.newpipe.extractor.StreamingService.LinkType.NONE) return false;
        } catch (Exception ignored) {}
        try {
            if (org.schabi.newpipe.extractor.ServiceList.SoundCloud.getLinkTypeByUrl(input)
                    != org.schabi.newpipe.extractor.StreamingService.LinkType.NONE) return false;
        } catch (Exception ignored) {}
        return true;
    }

    static Result inspect(String input) throws IOException {
        if (!isWebUrl(input)) throw new IOException("Enter a valid http(s) media link");
        final String url = safeUrl(input);
        try (Response response = request(url)) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Public video source is unavailable (HTTP " + response.code() + ")");
            }
            final String finalUrl = response.request().url().toString();
            final String mediaType = contentType(response.header("Content-Type"));
            if (mediaType.startsWith("video/") || (mediaType.isEmpty()
                    && extensionFor(finalUrl) != null)) {
                final Video direct = video(finalUrl, mediaType);
                if (direct == null) throw new IOException("Unsupported video type");
                return new Result(fileTitle(finalUrl), Collections.singletonList(direct));
            }
            if (!"text/html".equals(mediaType)
                    && !"application/xhtml+xml".equals(mediaType)) {
                throw new IOException("The link is not a downloadable video or HTML video page");
            }
            final byte[] data = response.body().source().readByteArray(MAX_HTML_BYTES + 1L);
            if (data.length > MAX_HTML_BYTES) {
                throw new IOException("The video page is too large to inspect safely");
            }
            final String html = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            final Result parsed = parseHtml(finalUrl, html);
            if (parsed.videos.isEmpty()) {
                throw new IOException("No direct public HTML5 video found on this page");
            }
            return parsed;
        }
    }

    /** Bounded, non-script HTML metadata parser. Does not follow iframe embeds. */
    static Result parseHtml(String pageUrl, String html) {
        final List<Video> videos = new ArrayList<>();
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        String title = null;
        final Matcher tags = TAG.matcher(html);
        while (tags.find() && videos.size() < MAX_SOURCES) {
            final String type = tags.group(1).toLowerCase(Locale.ROOT);
            final Map<String, String> attrs = attributes(tags.group());
            String src = null;
            if ("meta".equals(type)) {
                final String property = attrs.containsKey("property")
                        ? attrs.get("property") : attrs.get("name");
                if (property == null) continue;
                final String key = property.toLowerCase(Locale.ROOT);
                if ("og:title".equals(key) && title == null) {
                    title = cleanTitle(attrs.get("content"));
                }
                if ("og:video".equals(key) || "og:video:url".equals(key)
                        || "og:video:secure_url".equals(key)
                        || "twitter:player:stream".equals(key)) {
                    src = attrs.get("content");
                }
            } else {
                src = attrs.get("src");
            }
            if (src == null) continue;
            try {
                final String resolved = safeUrl(new URI(pageUrl).resolve(unescape(src)).toString());
                final Video video = video(resolved, attrs.get("type"));
                if (video != null && seen.add(video.url)) videos.add(video);
            } catch (Exception ignored) {
                // Relative, malformed, local and non-file sources are not downloadable.
            }
        }
        if (title == null || title.isEmpty()) {
            final Matcher match = TITLE.matcher(html);
            title = match.find() ? cleanTitle(match.group(1)) : null;
        }
        if (title == null || title.isEmpty()) title = fileTitle(pageUrl);
        return new Result(title, videos);
    }

    private static Map<String, String> attributes(String tag) {
        final Map<String, String> out = new java.util.HashMap<>();
        final Matcher matcher = ATTR.matcher(tag);
        while (matcher.find()) {
            final String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            out.put(matcher.group(1).toLowerCase(Locale.ROOT), unescape(value));
        }
        return out;
    }

    static Video video(String input, String declaredMime) {
        if (!isWebUrl(input)) return null;
        final String ext = extensionFor(input);
        final String mime = contentType(declaredMime);
        if (ext == null && !"video/mp4".equals(mime)
                && !"video/webm".equals(mime) && !"video/quicktime".equals(mime)) {
            return null;
        }
        final String suffix = ext == null ? ("video/webm".equals(mime) ? "webm"
                : "video/quicktime".equals(mime) ? "mov" : "mp4") : ext;
        final String resolvedMime = "webm".equals(suffix) ? "video/webm"
                : "mov".equals(suffix) ? "video/quicktime" : "video/mp4";
        if (!mime.isEmpty() && !mime.equals(resolvedMime)
                && !mime.equals("application/octet-stream")) return null;
        return new Video(input, suffix.toUpperCase(Locale.ROOT), suffix, resolvedMime);
    }

    private static String extensionFor(String link) {
        final String path;
        try { path = new URI(link).getPath().toLowerCase(Locale.ROOT); }
        catch (Exception invalid) { return null; }
        for (String suffix : new String[]{"mp4", "webm", "mov", "m4v"}) {
            if (path != null && path.endsWith("." + suffix)) {
                return "m4v".equals(suffix) ? "mp4" : suffix;
            }
        }
        return null;
    }

    private static String contentType(String header) {
        return header == null ? "" : header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String unescape(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String cleanTitle(String value) {
        if (value == null) return null;
        return Streams.sanitize(unescape(value.replaceAll("<[^>]+>", " "))
                .replaceAll("[\\p{Cntrl}]", " ").trim());
    }

    private static String fileTitle(String url) {
        try {
            final String path = new URI(url).getPath();
            final String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
            final String base = name.replaceAll("(?i)\\.(mp4|webm|mov|m4v)$", "");
            return Streams.sanitize(base.isEmpty() ? "Public video" : base);
        } catch (Exception ignored) { return "Public video"; }
    }

    /**
     * Requests only public destinations. Redirect targets are validated separately.
     * No app cookies, authentication headers or inherited site credentials are used.
     */
    private static Response request(String url) throws IOException {
        String current = safeUrl(url);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            final Request req = new Request.Builder().url(current)
                    .header("User-Agent", Net.USER_AGENT)
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=0-" + MAX_HTML_BYTES).build();
            final Response response = HTTP.newCall(req).execute();
            if (response.code() == 301 || response.code() == 302
                    || response.code() == 303 || response.code() == 307
                    || response.code() == 308) {
                final String target = response.header("Location");
                response.close();
                if (target == null) throw new IOException("Video redirect missing destination");
                try {
                    current = safeUrl(new URI(current).resolve(target).toString());
                } catch (java.net.URISyntaxException invalid) {
                    throw new IOException("Invalid video redirect", invalid);
                }
            } else {
                return response;
            }
        }
        throw new IOException("Too many video redirects");
    }

    private static String safeUrl(String input) throws IOException {
        try {
            final URI uri = new URI(input);
            if (!isWebUrl(input) || uri.getRawFragment() != null
                    || uri.getHost().length() > 253 || uri.getPort() > 65535
                    || uri.getPort() == 0) throw new IOException("Invalid public video URL");
            final String host = uri.getHost().toLowerCase(Locale.ROOT);
            if ("localhost".equals(host) || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal")) {
                throw new IOException("Local network video URLs are not supported");
            }
            return uri.toASCIIString();
        } catch (java.net.URISyntaxException invalid) {
            throw new IOException("Invalid video URL", invalid);
        }
    }

    private static final class PublicDns implements Dns {
        @Override public List<InetAddress> lookup(String hostname)
                throws java.net.UnknownHostException {
            final List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            final List<InetAddress> publicAddresses = new ArrayList<>();
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    throw new java.net.UnknownHostException("Non-public video destination");
                }
                publicAddresses.add(address);
            }
            return publicAddresses;
        }
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress()) return false;
        final byte[] ip = address.getAddress();
        if (ip.length == 4) {
            final int first = ip[0] & 255, second = ip[1] & 255;
            return first != 0 && first != 10 && first != 127
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && second == 168)
                    && !(first == 198 && (second == 18 || second == 19))
                    && first < 224;
        }
        return ip.length == 16 && (ip[0] & 0xfe) != 0xfc
                && !(ip[0] == 0 && ip[1] == 0 && ip[15] == 1);
    }
}
