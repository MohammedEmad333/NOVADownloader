package com.example.ytdl;

import android.content.Context;
import java.util.Locale;

/** Brief actionable error categories; never display signed media URLs or credentials. */
final class SourceFeedback {
    private SourceFeedback() {}

    static String message(Context context, String input, Exception error) {
        final String raw = error == null || error.getMessage() == null ? ""
                : error.getMessage().toLowerCase(Locale.ROOT);
        final boolean isX = TwitterVideo.isPostUrl(input);
        final boolean isMega = MegaPublicFile.isMegaUrl(input);
        if (isMega && (raw.contains("missing its decryption key")
                || raw.contains("invalid mega") || raw.contains("wrong mega file key"))) {
            return NovaPreferences.text(context,
                    "This MEGA link has an invalid or missing file key.",
                    "مفتاح ملف MEGA مفقود أو غير صالح.");
        }
        if (isMega && (raw.contains("quota") || raw.contains("509")
                || raw.contains("over quota"))) {
            return NovaPreferences.text(context,
                    "MEGA transfer quota reached. Try again later.",
                    "تم بلوغ حد نقل MEGA. حاول لاحقًا.");
        }
        if (isMega && (raw.contains("unsupported") || raw.contains("not a supported video"))) {
            return NovaPreferences.text(context,
                    "This MEGA link is not a supported public video file.",
                    "رابط MEGA لا يحتوي ملف فيديو عام مدعوم.");
        }
        if (raw.contains("429") || raw.contains("rate limit")) {
            return NovaPreferences.text(context,
                    "The public metadata provider is busy. Try again later.",
                    "خدمة بيانات الفيديو مشغولة. حاول لاحقًا.");
        }
        if (raw.contains("timeout") || raw.contains("timed out")
                || raw.contains("unable to resolve host") || raw.contains("network")
                || raw.contains("connect")) {
            return NovaPreferences.text(context,
                    "Connection unavailable. Check your internet and retry.",
                    "الاتصال غير متاح. تحقق من الإنترنت وأعد المحاولة.");
        }
        if (isX && (raw.contains("no public") || raw.contains("unavailable")
                || raw.contains("no accessible media"))) {
            return NovaPreferences.text(context,
                    "No accessible public video was found. Check the post and try another public link.",
                    "لم يُعثر على فيديو عام متاح. تحقق من المنشور أو جرّب رابطًا عامًا آخر.");
        }
        if (isX && (raw.contains("provider") || raw.contains("metadata")
                || raw.contains("http "))) {
            return NovaPreferences.text(context,
                    "The public X video service could not respond. Retry later.",
                    "تعذّر الاتصال بخدمة فيديو إكس العامة. أعد المحاولة لاحقًا.");
        }
        return NovaPreferences.text(context,
                "Could not read this link or search. Check it and try again.",
                "تعذّرت قراءة الرابط أو البحث. تحقق منه وأعد المحاولة.");
    }
}
