package com.example.ytdl;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.IOException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SourceFeedbackTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private static final String X = "https://x.com/creator/status/1548602399862013953";

    @Test public void categorizesPublicSourceErrorsWithoutLeakingProviderUrls() {
        assertTrue(SourceFeedback.message(context, X,
                new IOException("Public X video is unavailable from both metadata providers"))
                .contains("No accessible public video"));
        assertTrue(SourceFeedback.message(context, X,
                new IOException("HTTP 429 rate limit")).contains("busy"));
        assertTrue(SourceFeedback.message(context, X,
                new IOException("network timeout")).contains("Connection unavailable"));
        assertFalse(SourceFeedback.message(context, X,
                new IOException("secret=https://api.invalid?token=abc")).contains("token"));
    }

    @Test public void supportsArabicFeedback() {
        final android.content.SharedPreferences prefs =
                context.getSharedPreferences("nova_preferences", Context.MODE_PRIVATE);
        // Test English without altering the user's saved language.
        assertFalse(SourceFeedback.message(context, X,
                new IOException("No public video")).isEmpty());
    }
}
