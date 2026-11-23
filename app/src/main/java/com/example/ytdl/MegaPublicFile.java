package com.example.ytdl;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Public, single-file MEGA links. File keys are held locally and never sent to
 * the MEGA API. Downloaded ciphertext is decrypted and MAC-verified on device
 * before publishing to MediaStore. No account access or protected folder support.
 */
final class MegaPublicFile {
    private static final int MAX_API_BYTES = 64 * 1024;
    private static final int TRANSFER_CHUNK = 1024 * 1024;
    private static final long STORAGE_RESERVE = 32L * 1024 * 1024;
    private static final String API = "https://g.api.mega.co.nz/cs";
    private static final AtomicLong API_SEQUENCE = new AtomicLong(System.currentTimeMillis());

    private static final OkHttpClient HTTP = Net.HTTP.newBuilder()
            .dns(new PublicDns())
            .followSslRedirects(false)
            .build();

    static final class Link {
        final String handle;
        final byte[] fullKey;

        Link(String handle, byte[] fullKey) {
            this.handle = handle;
            this.fullKey = fullKey.clone();
        }
    }

    static final class Info {
        final String name;
        final String title;
        final String extension;
        final String mime;
        final long size;
        final String downloadUrl;

        Info(String name, String title, String extension, String mime,
             long size, String downloadUrl) {
            this.name = name;
            this.title = title;
            this.extension = extension;
            this.mime = mime;
            this.size = size;
            this.downloadUrl = downloadUrl;
        }
    }

    private MegaPublicFile() {}

    /** Only MEGA's public share hosts, never similar-looking subdomains. */
    static boolean isMegaUrl(String input) {
        if (input == null) return false;
        try {
            final URI url = new URI(input.trim());
            if (!"https".equalsIgnoreCase(url.getScheme())
                    || url.getRawUserInfo() != null
                    || url.getPort() != -1 || url.getHost() == null) return false;
            final String host = url.getHost().toLowerCase(Locale.ROOT);
            return host.equals("mega.nz") || host.equals("www.mega.nz")
                    || host.equals("mega.co.nz") || host.equals("www.mega.co.nz");
        } catch (Exception ignored) {
            return false;
        }
    }

    static Link parse(String input) throws IOException {
        if (!isMegaUrl(input)) throw new IOException("Use a public HTTPS MEGA file link");
        final URI uri;
        try { uri = new URI(input.trim()); }
        catch (Exception ignored) { throw new IOException("Invalid MEGA file link"); }
        if (uri.getRawQuery() != null) {
            throw new IOException("Unsupported MEGA file link format");
        }
        String handle;
        String encodedKey;
        final String path = uri.getPath();
        final String fragment = uri.getRawFragment();
        if (path != null && path.matches("/file/[A-Za-z0-9_-]{8,12}/?")) {
            final String[] parts = path.split("/");
            handle = parts[2];
            encodedKey = fragment;
        } else if ((path == null || path.equals("/") || path.isEmpty())
                && fragment != null && fragment.matches("![A-Za-z0-9_-]{8,12}![A-Za-z0-9_-]{43}")) {
            final String[] parts = fragment.substring(1).split("!");
            handle = parts[0];
            encodedKey = parts[1];
        } else {
            throw new IOException("Only individual public MEGA files are supported");
        }
        if (encodedKey == null || !encodedKey.matches("[A-Za-z0-9_-]{43}")) {
            throw new IOException("MEGA file link is missing its decryption key");
        }
        try {
            final byte[] decoded = Base64.getUrlDecoder().decode(encodedKey);
            if (decoded.length != 32) throw new IOException("Invalid MEGA link key");
            return new Link(handle, decoded);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid MEGA link key");
        }
    }

    static Info inspect(String shareUrl) throws IOException {
        final Link link = parse(shareUrl);
        final JSONObject command = new JSONObject();
        try {
            command.put("a", "g");
            command.put("g", 1);
            command.put("p", link.handle);
        } catch (JSONException error) {
            throw new IOException("Failed to prepare MEGA metadata", error);
        }

        // The public handle (not the link fragment / file key) is sent.
        final String json = new JSONArray().put(command).toString();
        final RequestBody body = RequestBody.create(json,
                MediaType.parse("application/json; charset=utf-8"));
        final Request request = new Request.Builder()
                .url(API + "?id=" + API_SEQUENCE.incrementAndGet())
                .post(body).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("MEGA metadata is unavailable (HTTP " + response.code() + ")");
            }
            final byte[] bytes = response.body().source().readByteArray(MAX_API_BYTES + 1L);
            if (bytes.length > MAX_API_BYTES) {
                throw new IOException("MEGA metadata response is too large");
            }
            final JSONArray array = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            if (array.length() < 1 || !(array.opt(0) instanceof JSONObject)) {
                throw new IOException("MEGA public file is unavailable or over quota");
            }
            return parseResponse(link, array.getJSONObject(0));
        } catch (JSONException badResponse) {
            throw new IOException("MEGA returned invalid public metadata", badResponse);
        }
    }

    /** Separated for offline API/crypto fixtures, without real share links. */
    static Info parseResponse(Link link, JSONObject result) throws IOException {
        try {
            final long size = result.optLong("s", -1);
            if (size <= 0) throw new IOException("MEGA file size is unavailable");
            final String linkUrl = safeDownloadUrl(result.optString("g", ""));
            final String attrs = result.optString("at", "");
            final byte[] encryptedAttrs = decodeBase64(attrs);
            if (encryptedAttrs.length == 0 || encryptedAttrs.length % 16 != 0
                    || encryptedAttrs.length > 16 * 1024) {
                throw new IOException("MEGA file attributes are invalid");
            }
            final Key key = key(link.fullKey);
            final Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.aes, "AES"),
                    new IvParameterSpec(new byte[16]));
            final byte[] decrypted = cipher.doFinal(encryptedAttrs);
            int validLength = decrypted.length;
            for (int i = 0; i < decrypted.length; i++) {
                if (decrypted[i] == 0) { validLength = i; break; }
            }
            if (validLength <= 5 || decrypted[0] != 'M' || decrypted[1] != 'E'
                    || decrypted[2] != 'G' || decrypted[3] != 'A') {
                throw new IOException("Wrong MEGA file key or invalid attributes");
            }
            final JSONObject json = new JSONObject(new String(
                    decrypted, 4, validLength - 4, StandardCharsets.UTF_8));
            final String rawName = json.optString("n", "");
            if (rawName.isEmpty()) throw new IOException("MEGA filename is unavailable");
            final String name = Streams.sanitize(rawName.replaceAll("[\\p{Cntrl}]", ""));
            final String lower = name.toLowerCase(Locale.ROOT);
            final String extension;
            final String mime;
            if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) {
                extension = lower.endsWith(".m4v") ? "m4v" : "mp4";
                mime = "video/mp4";
            } else if (lower.endsWith(".webm")) {
                extension = "webm";
                mime = "video/webm";
            } else if (lower.endsWith(".mov")) {
                extension = "mov";
                mime = "video/quicktime";
            } else {
                throw new IOException("MEGA public file is not a supported video format");
            }
            final String title = name.substring(0, name.length() - extension.length() - 1);
            return new Info(name, Streams.sanitize(title), extension, mime, size, linkUrl);
        } catch (GeneralSecurityException | JSONException | IllegalArgumentException error) {
            throw new IOException("Unable to decrypt MEGA file metadata", error);
        }
    }

    /**
     * MEGA uses /start-end on its temporary encrypted download URL, rather
     * than relying on generic HTTP Range responses. Chunks are restartable.
     */
    static void downloadEncrypted(String downloadUrl, File output, long size,
                                  Net.Progress progress, Net.Control control)
            throws IOException {
        final String safe = safeDownloadUrl(downloadUrl);
        if (size <= 0) throw new IOException("Missing MEGA file length");
        long written = output.exists() ? output.length() : 0;
        if (written > size) throw new Net.UnsafeResume("MEGA partial exceeds file size");
        if (written == size) {
            progress.onProgress(written, size);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(output, true)) {
            while (written < size) {
                control.checkpoint();
                final long end = Math.min(size - 1, written + TRANSFER_CHUNK - 1L);
                final long requested = end - written + 1;
                final HttpUrl base = HttpUrl.parse(safe);
                if (base == null) throw new IOException("Invalid MEGA transfer endpoint");
                final String segmentUrl = base.newBuilder()
                        .addPathSegment(written + "-" + end).build().toString();
                final Request request = new Request.Builder().url(segmentUrl)
                        .header("Accept-Encoding", "identity")
                        .header("User-Agent", Net.USER_AGENT).build();
                try (Response response = HTTP.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("HTTP " + response.code() + " from MEGA transfer");
                    }
                    final String encoding = response.header("Content-Encoding");
                    if (encoding != null && !"identity".equalsIgnoreCase(encoding.trim())) {
                        throw new Net.UnsafeResume("MEGA transfer returned compressed bytes");
                    }
                    long received = 0;
                    try (InputStream in = response.body().byteStream()) {
                        final byte[] buffer = new byte[64 * 1024];
                        while (received < requested) {
                            control.checkpoint();
                            final int count = in.read(buffer, 0,
                                    (int) Math.min(buffer.length, requested - received));
                            if (count == -1) break;
                            if (count == 0) continue;
                            out.write(buffer, 0, count);
                            written += count;
                            received += count;
                            progress.onProgress(written, size);
                        }
                        if (received != requested || in.read() != -1) {
                            throw new Net.UnsafeResume("MEGA transfer chunk length mismatch");
                        }
                    }
                }
            }
        }
    }

    /**
     * Do not publish a byte until the full MEGA meta-MAC is valid. A paused
     * encrypted partial can be resumed and verified from byte zero later.
     */
    static void decryptAndVerify(File encrypted, File plaintext, String shareUrl,
                                 long expectedSize, Net.Control control) throws IOException {
        final Link link = parse(shareUrl);
        if (expectedSize <= 0 || encrypted.length() != expectedSize) {
            throw new Net.UnsafeResume("MEGA encrypted file is incomplete");
        }
        final Key key = key(link.fullKey);
        if (plaintext.getParentFile() != null) {
            final long available = plaintext.getParentFile().getUsableSpace();
            if (available > 0 && available < expectedSize + STORAGE_RESERVE) {
                throw new IOException("Not enough storage to verify MEGA video");
            }
        }
        final SecretKeySpec aesKey = new SecretKeySpec(key.aes, "AES");
        final byte[] meta = new byte[16];
        final byte[] input = new byte[TRANSFER_CHUNK];
        final byte[] chunkState = new byte[16];
        final byte[] macBlock = new byte[16];
        boolean verified = false;
        try (FileInputStream in = new FileInputStream(encrypted);
             FileOutputStream out = new FileOutputStream(plaintext, false)) {
            final Cipher macCipher = Cipher.getInstance("AES/ECB/NoPadding");
            macCipher.init(Cipher.ENCRYPT_MODE, aesKey);
            long position = 0;
            int chunkNumber = 1;
            while (position < expectedSize) {
                control.checkpoint();
                final int size = (int) Math.min(expectedSize - position,
                        Math.min(chunkNumber, 8) * 128L * 1024);
                int filled = 0;
                while (filled < size) {
                    control.checkpoint();
                    final int n = in.read(input, filled, size - filled);
                    if (n < 0) throw new IOException("Encrypted MEGA file is incomplete");
                    filled += n;
                }
                final Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
                final byte[] iv = new byte[16];
                System.arraycopy(key.nonce, 0, iv, 0, 8);
                long blockOffset = position / 16;
                for (int i = 15; i >= 8; i--) {
                    iv[i] = (byte) blockOffset;
                    blockOffset >>>= 8;
                }
                ctr.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
                final byte[] clear = ctr.doFinal(input, 0, size);
                // MEGA chunk CBC-MAC IV = nonce || nonce.
                System.arraycopy(key.nonce, 0, chunkState, 0, 8);
                System.arraycopy(key.nonce, 0, chunkState, 8, 8);
                for (int i = 0; i < clear.length; i += 16) {
                    Arrays.fill(macBlock, (byte) 0);
                    System.arraycopy(clear, i, macBlock, 0,
                            Math.min(16, clear.length - i));
                    for (int j = 0; j < 16; j++) macBlock[j] ^= chunkState[j];
                    System.arraycopy(macCipher.doFinal(macBlock), 0,
                            chunkState, 0, 16);
                }
                for (int i = 0; i < 16; i++) macBlock[i] = (byte) (meta[i] ^ chunkState[i]);
                System.arraycopy(macCipher.doFinal(macBlock), 0, meta, 0, 16);
                out.write(clear);
                position += size;
                if (chunkNumber < 8) chunkNumber++;
            }
            final byte[] computed = new byte[8];
            for (int i = 0; i < 4; i++) {
                computed[i] = (byte) (meta[i] ^ meta[i + 4]);
                computed[i + 4] = (byte) (meta[i + 8] ^ meta[i + 12]);
            }
            verified = MessageDigest.isEqual(computed, key.expectedMac);
            if (!verified) throw new IOException("MEGA file integrity verification failed");
            control.checkpoint();
        } catch (GeneralSecurityException crypto) {
            throw new IOException("Unable to decrypt MEGA file safely", crypto);
        } finally {
            if (!verified) plaintext.delete();
        }
    }

    private static byte[] decodeBase64(String encoded) throws IOException {
        try { return Base64.getUrlDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid MEGA encrypted attributes");
        }
    }

    private static String safeDownloadUrl(String input) throws IOException {
        try {
            final URI url = new URI(input);
            if (!"https".equalsIgnoreCase(url.getScheme())
                    || url.getHost() == null || url.getRawUserInfo() != null
                    || url.getRawFragment() != null || url.getPort() == 0) {
                throw new IOException("Invalid secure MEGA download URL");
            }
            return url.toASCIIString();
        } catch (java.net.URISyntaxException bad) {
            throw new IOException("Invalid secure MEGA download URL");
        }
    }

    static Key key(byte[] full) throws IOException {
        if (full == null || full.length != 32) throw new IOException("Invalid MEGA file key");
        final byte[] aes = new byte[16];
        for (int i = 0; i < 16; i++) aes[i] = (byte) (full[i] ^ full[i + 16]);
        return new Key(aes, Arrays.copyOfRange(full, 16, 24),
                Arrays.copyOfRange(full, 24, 32));
    }

    static final class Key {
        final byte[] aes;
        final byte[] nonce;
        final byte[] expectedMac;
        Key(byte[] aes, byte[] nonce, byte[] expectedMac) {
            this.aes = aes;
            this.nonce = nonce;
            this.expectedMac = expectedMac;
        }
    }

    private static final class PublicDns implements Dns {
        @Override public List<InetAddress> lookup(String hostname)
                throws java.net.UnknownHostException {
            final List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            for (InetAddress address : addresses) {
                if (!PublicVideoLinks.isPublicAddress(address)) {
                    throw new java.net.UnknownHostException("Non-public MEGA destination");
                }
            }
            return addresses;
        }
    }
}
