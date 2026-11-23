package com.example.ytdl;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class MegaPublicFileTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static String encoded(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String link(byte[] key) {
        return "https://mega.nz/file/AbCdEf12#" + encoded(key);
    }

    private static byte[] defaultKey() {
        final byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 11);
        return key;
    }

    @Test public void parsesPublicLinksAndNeverTreatsPrivateHostsAsMega() throws Exception {
        final byte[] key = defaultKey();
        final MegaPublicFile.Link modern = MegaPublicFile.parse(link(key));
        assertEquals("AbCdEf12", modern.handle);
        assertArrayEquals(key, modern.fullKey);
        final MegaPublicFile.Link legacy = MegaPublicFile.parse(
                "https://mega.nz/#!AbCdEf12!" + encoded(key));
        assertEquals("AbCdEf12", legacy.handle);
        assertArrayEquals(key, legacy.fullKey);
        for (String invalid : new String[]{
                "http://mega.nz/file/AbCdEf12#" + encoded(key),
                "https://mega.nz.evil.invalid/file/AbCdEf12#" + encoded(key),
                "https://mega.nz@evil.invalid/file/AbCdEf12#" + encoded(key),
                "https://mega.nz/folder/AbCdEf12#" + encoded(key),
                "https://mega.nz/file/AbCdEf12",
                "https://mega.nz/file/AbCdEf12#not-a-valid-key"}) {
            try {
                MegaPublicFile.parse(invalid);
                fail("Accepted unsupported or invalid MEGA share");
            } catch (java.io.IOException expected) { /* fail closed */ }
        }
        assertEquals(link(key), LinkParser.firstSupportedMediaLink(link(key)));
    }

    @Test public void decodesEncryptedAttributesAndVideoMetadataOffline() throws Exception {
        final byte[] full = defaultKey();
        final MegaPublicFile.Key secret = MegaPublicFile.key(full);
        final String attrs = "MEGA{\"n\":\"09 FHD.mp4\"}";
        final byte[] plain = Arrays.copyOf(attrs.getBytes(StandardCharsets.UTF_8),
                ((attrs.length() + 15) / 16) * 16);
        final Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret.aes, "AES"),
                new IvParameterSpec(new byte[16]));
        final String encodedAttrs = encoded(cipher.doFinal(plain));
        final JSONObject metadata = new JSONObject()
                .put("s", 1024).put("g", "https://download.mega.nz/file-id")
                .put("at", encodedAttrs);
        final MegaPublicFile.Info info = MegaPublicFile.parseResponse(
                MegaPublicFile.parse(link(full)), metadata);
        assertEquals("09 FHD.mp4", info.name);
        assertEquals("09 FHD", info.title);
        assertEquals("mp4", info.extension);
        assertEquals("video/mp4", info.mime);
        assertEquals(1024, info.size);

        final byte[] wrongKey = full.clone();
        wrongKey[0] ^= 0x3a;
        try {
            MegaPublicFile.parseResponse(MegaPublicFile.parse(link(wrongKey)), metadata);
            fail("Incorrect key decrypted file attributes");
        } catch (java.io.IOException expected) { /* fail closed */ }
    }

    @Test public void rejectsUnsupportedMediaAndInvalidApiUrl() throws Exception {
        final byte[] full = defaultKey();
        final MegaPublicFile.Key secret = MegaPublicFile.key(full);
        final String attrs = "MEGA{\"n\":\"private.dat\"}";
        final byte[] plain = Arrays.copyOf(attrs.getBytes(StandardCharsets.UTF_8), 32);
        final Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret.aes, "AES"),
                new IvParameterSpec(new byte[16]));
        final JSONObject metadata = new JSONObject().put("s", 1024)
                .put("g", "https://download.mega.nz/file-id")
                .put("at", encoded(cipher.doFinal(plain)));
        try {
            MegaPublicFile.parseResponse(MegaPublicFile.parse(link(full)), metadata);
            fail("Non-video file was not rejected");
        } catch (java.io.IOException expected) { /* unsupported type */ }
        metadata.put("g", "http://127.0.0.1/a");
        try {
            MegaPublicFile.parseResponse(MegaPublicFile.parse(link(full)), metadata);
            fail("Insecure MEGA download URL was accepted");
        } catch (java.io.IOException expected) { /* fail closed */ }
    }

    @Test public void decryptsAndAuthenticatesSyntheticEncryptedVideo() throws Exception {
        // A local fixture, NOT a third-party file. File-MAC is independently
        // assembled using AES-CBC chunk chains, then encoded in the link key.
        final byte[] clear = new byte[370123];
        for (int i = 0; i < clear.length; i++) clear[i] = (byte) (i * 31 + 7);
        final byte[] aes = new byte[16];
        final byte[] nonce = new byte[8];
        for (int i = 0; i < aes.length; i++) aes[i] = (byte) (i * 9 + 4);
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (i * 7 + 3);
        final byte[] meta = new byte[16];
        final Cipher block = Cipher.getInstance("AES/ECB/NoPadding");
        block.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aes, "AES"));
        int at = 0;
        for (int chunk = 1; at < clear.length; chunk++) {
            final int count = Math.min(clear.length - at, chunk * 128 * 1024);
            final byte[] state = new byte[16];
            System.arraycopy(nonce, 0, state, 0, 8);
            System.arraycopy(nonce, 0, state, 8, 8);
            for (int offset = 0; offset < count; offset += 16) {
                final byte[] blockInput = new byte[16];
                System.arraycopy(clear, at + offset, blockInput, 0,
                        Math.min(16, count - offset));
                for (int i = 0; i < 16; i++) blockInput[i] ^= state[i];
                System.arraycopy(block.doFinal(blockInput), 0, state, 0, 16);
            }
            for (int i = 0; i < 16; i++) state[i] ^= meta[i];
            System.arraycopy(block.doFinal(state), 0, meta, 0, 16);
            at += count;
        }
        final byte[] full = new byte[32];
        for (int i = 0; i < 16; i++) full[i] = (byte) (aes[i] ^ (i < 8 ? nonce[i] : meta[i-8]));
        System.arraycopy(nonce, 0, full, 16, 8);
        for (int i = 0; i < 4; i++) {
            full[24 + i] = (byte) (meta[i] ^ meta[i + 4]);
            full[28 + i] = (byte) (meta[8 + i] ^ meta[12 + i]);
        }
        // MEGA key[0..15] ^ key[16..31] must be AES key. The
        // "MAC" suffix is itself the folded meta-MAC above.
        for (int i = 0; i < 16; i++) full[i] = (byte) (aes[i] ^ full[16 + i]);

        final byte[] iv = new byte[16];
        System.arraycopy(nonce, 0, iv, 0, 8);
        final Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aes, "AES"),
                new IvParameterSpec(iv));
        final byte[] encrypted = ctr.doFinal(clear);
        final File encryptedFile = folder.newFile("video.encrypted");
        Files.write(encryptedFile.toPath(), encrypted);
        final File output = folder.newFile("video.verified");
        MegaPublicFile.decryptAndVerify(encryptedFile, output, link(full),
                clear.length, () -> {});
        assertArrayEquals(clear, Files.readAllBytes(output.toPath()));

        encrypted[50] ^= 1;
        Files.write(encryptedFile.toPath(), encrypted);
        try {
            MegaPublicFile.decryptAndVerify(encryptedFile, output, link(full),
                    clear.length, () -> {});
            fail("Tampered ciphertext verified");
        } catch (java.io.IOException expected) {
            assertFalse("Unverified output leaked to disk", output.exists());
        }
    }
}
