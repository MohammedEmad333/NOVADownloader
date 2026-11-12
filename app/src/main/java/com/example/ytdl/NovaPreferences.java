package com.example.ytdl;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** User-owned UI and download-format preferences; no permissions or cloud storage needed. */
public final class NovaPreferences {
    private static final String PREFS = "nova_ui_prefs";
    private static final String LANGUAGE = "language";
    private static final String VIDEO_HEIGHT = "video_height";
    private static final String MP3_RATE = "mp3_bitrate";

    private NovaPreferences() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean arabic(Context context) {
        return "ar".equals(language(context));
    }

    public static String language(Context context) {
        final String saved = prefs(context).getString(LANGUAGE, "system");
        if ("ar".equals(saved) || "en".equals(saved)) return saved;
        return Locale.getDefault().getLanguage().equals("ar") ? "ar" : "en";
    }

    public static String languageChoice(Context context) {
        return prefs(context).getString(LANGUAGE, "system");
    }

    public static void setLanguage(Context context, String value) {
        if (!"ar".equals(value) && !"en".equals(value)) value = "system";
        prefs(context).edit().putString(LANGUAGE, value).apply();
    }

    public static String text(Context context, String english, String arabic) {
        return arabic(context) ? arabic : english;
    }

    public static int videoHeight(Context context) {
        final int value = prefs(context).getInt(VIDEO_HEIGHT, 720);
        return validVideoHeight(value) ? value : 720;
    }

    public static boolean validVideoHeight(int value) {
        return value == 360 || value == 480 || value == 720
                || value == 1080 || value == 2160;
    }

    public static void setVideoHeight(Context context, int value) {
        if (!validVideoHeight(value)) throw new IllegalArgumentException("Invalid video quality");
        prefs(context).edit().putInt(VIDEO_HEIGHT, value).apply();
    }

    public static int mp3Bitrate(Context context) {
        final int value = prefs(context).getInt(MP3_RATE, 192);
        return validMp3Rate(value) ? value : 192;
    }

    public static boolean validMp3Rate(int value) {
        return value == 128 || value == 192 || value == 320;
    }

    public static void setMp3Bitrate(Context context, int value) {
        if (!validMp3Rate(value)) throw new IllegalArgumentException("Invalid MP3 bitrate");
        prefs(context).edit().putInt(MP3_RATE, value).apply();
    }

    /** Preferred formats sort first without hiding any other available quality. */
    static int formatPriority(Context context, DownloadService.Kind kind,
                              int height, int mp3Rate) {
        if (kind == DownloadService.Kind.MP3 && mp3Rate == mp3Bitrate(context)) return 0;
        if ((kind == DownloadService.Kind.MERGE || kind == DownloadService.Kind.MUXED)
                && height == videoHeight(context)) return 0;
        return 1;
    }
}
