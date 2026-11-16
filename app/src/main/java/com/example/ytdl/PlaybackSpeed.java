package com.example.ytdl;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Locally stored, bounded playback speed shared by offline video and audio. */
final class PlaybackSpeed {
    private static final String PREFS = "nova_playback";
    private static final String KEY = "speed_index";
    private static final float[] VALUES = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    static final int DEFAULT = 1;

    private PlaybackSpeed() {}

    static int normalize(int index) {
        return index >= 0 && index < VALUES.length ? index : DEFAULT;
    }

    static int next(int index) {
        return (normalize(index) + 1) % VALUES.length;
    }

    static float value(int index) {
        return VALUES[normalize(index)];
    }

    static String label(int index) {
        final float speed = value(index);
        return speed == Math.round(speed)
                ? String.format(Locale.US, "%dx", (int) speed)
                : String.format(Locale.US, "%sx", Float.toString(speed));
    }

    static int load(Context context) {
        try {
            return normalize(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY, DEFAULT));
        } catch (ClassCastException invalid) {
            return DEFAULT;
        }
    }

    static void save(Context context, int index) {
        if (index != normalize(index)) {
            throw new IllegalArgumentException("Unsupported playback speed");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY, index).apply();
    }

    static boolean canEnterPip(boolean video, boolean prepared, boolean playing) {
        return video && prepared && playing;
    }
}
