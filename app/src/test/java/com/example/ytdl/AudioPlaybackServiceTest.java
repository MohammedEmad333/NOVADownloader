package com.example.ytdl;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class AudioPlaybackServiceTest {
    private static DownloadService.Task saved(String uri, String mime) {
        final DownloadService.Task task = new DownloadService.Task(
                "https://example.com/original", "Offline media",
                DownloadService.Spec.audio(null, "Audio"));
        task.state = DownloadService.State.DONE;
        task.outputUri = uri;
        task.mimeType = mime;
        return task;
    }

    @Test public void requiresCompletedOwnedAudioNotRemoteUris() {
        assertTrue(AudioPlaybackService.eligible(
                saved("content://media/external/audio/media/100", "audio/mpeg")));
        assertTrue(AudioPlaybackService.eligible(
                saved("content://media/external/downloads/100", "audio/mp4")));
        assertFalse(AudioPlaybackService.eligible(
                saved("content://media/external/video/media/100", "video/mp4")));
        assertFalse(AudioPlaybackService.eligible(
                saved("https://example.com/audio.mp3", "audio/mpeg")));
        final DownloadService.Task queued = saved(
                "content://media/external/audio/media/100", "audio/mpeg");
        queued.state = DownloadService.State.QUEUED;
        assertFalse(AudioPlaybackService.eligible(queued));
    }

    @Test public void serviceStartIntentContainsOnlyLocalIdentityAndState() {
        final Context context = RuntimeEnvironment.getApplication();
        final Intent intent = AudioPlaybackService.startIntent(context, 123, 22_000, true);
        assertEquals(AudioPlaybackService.class.getName(),
                intent.getComponent().getClassName());
        assertEquals(AudioPlaybackService.ACTION_START, intent.getAction());
        assertNull(intent.getData());
        assertEquals(3, intent.getExtras().size());
        assertFalse(intent.toUri(0).contains("https://"));
        try {
            AudioPlaybackService.startIntent(context, 0, 0, true);
            fail("Invalid task ID accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void manifestDeclaresMediaPlaybackForegroundServiceAndPipActivity()
            throws Exception {
        java.nio.file.Path manifest = java.nio.file.Path.of(
                "src/main/AndroidManifest.xml");
        if (!java.nio.file.Files.isRegularFile(manifest)) {
            manifest = java.nio.file.Path.of("app/src/main/AndroidManifest.xml");
        }
        final String xml = java.nio.file.Files.readString(manifest);
        assertTrue(xml.contains("android:name=\".AudioPlaybackService\""));
        assertTrue(xml.contains("android:foregroundServiceType=\"mediaPlayback\""));
        assertTrue(xml.contains("android:name=\"android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK\""));
        assertTrue(xml.contains("android:supportsPictureInPicture=\"true\""));
        assertTrue(xml.contains("android:exported=\"false\""));
    }
}
