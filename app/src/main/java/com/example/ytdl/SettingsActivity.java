package com.example.ytdl;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;

/** Offline, on-device preferences for NOVA. All settings are reversible. */
public final class SettingsActivity extends Activity {
    private boolean firstLayout = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.applyChrome(this);
        buildScreen();
    }

    private String text(String en, String ar) {
        return NovaPreferences.text(this, en, ar);
    }

    private void buildScreen() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 19),
                Ui.dp(this, 18), Ui.dp(this, 28));
        root.setLayoutDirection(NovaPreferences.arabic(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.addView(root);

        final TextView back = Ui.chip(this,
                text("←  Back", "رجوع  →"), Ui.ACCENT);
        back.setMinHeight(Ui.dp(this, 48));
        back.setContentDescription(text("Back to downloader", "العودة إلى التطبيق"));
        back.setOnClickListener(v -> finish());
        root.addView(back);

        final LinearLayout heading = Ui.section(this,
                text("SETTINGS / 04", "الإعدادات / ٠٤"),
                text("Make NOVA yours", "اضبط NOVA على راحتك"),
                text("Preferences are saved on this device.",
                        "إعداداتك محفوظة على هذا الجهاز."));
        final LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = Ui.dp(this, 23);
        headingParams.bottomMargin = Ui.dp(this, 20);
        root.addView(heading, headingParams);

        addLabel(root, text("APP LANGUAGE", "لغة التطبيق"),
                text("Choose English, Arabic, or follow your phone.",
                        "اختر العربية أو الإنجليزية أو لغة جهازك."));
        final String[] languageKeys = {"system", "en", "ar"};
        final Spinner language = spinner(new String[]{
                text("System default", "حسب لغة الجهاز"),
                "English", "العربية"});
        root.addView(language, row());
        language.setSelection(indexOf(languageKeys, NovaPreferences.languageChoice(this)));
        language.setOnItemSelectedListener(new Selected() {
            @Override public void selected(int position) {
                final String next = languageKeys[position];
                if (!next.equals(NovaPreferences.languageChoice(SettingsActivity.this))) {
                    NovaPreferences.setLanguage(SettingsActivity.this, next);
                    buildScreen();
                }
            }
        });

        addLabel(root, text("PREFERRED VIDEO QUALITY", "جودة الفيديو المفضلة"),
                text("Matching video formats appear first when available.",
                        "تظهر الجودة المناسبة أولاً إن كانت متاحة."));
        final int[] qualityValues = {360, 480, 720, 1080, 2160};
        final Spinner quality = spinner(new String[]{
                "360p", "480p", "720p", "1080p", "2160p"});
        root.addView(quality, row());
        quality.setSelection(indexOf(qualityValues, NovaPreferences.videoHeight(this)));
        quality.setOnItemSelectedListener(new Selected() {
            @Override public void selected(int position) {
                NovaPreferences.setVideoHeight(SettingsActivity.this, qualityValues[position]);
            }
        });

        addLabel(root, text("PREFERRED MP3 QUALITY", "جودة MP3 المفضلة"),
                text("Available MP3 formats will show your preference first.",
                        "ستظهر جودة MP3 المفضلة أولاً."));
        final int[] mp3Values = {128, 192, 320};
        final Spinner mp3 = spinner(new String[]{"128 kbps", "192 kbps", "320 kbps"});
        root.addView(mp3, row());
        mp3.setSelection(indexOf(mp3Values, NovaPreferences.mp3Bitrate(this)));
        mp3.setOnItemSelectedListener(new Selected() {
            @Override public void selected(int position) {
                NovaPreferences.setMp3Bitrate(SettingsActivity.this, mp3Values[position]);
            }
        });

        addLabel(root, text("NETWORK", "الشبكة"),
                text("Save mobile data when Wi-Fi is unavailable.",
                        "حافظ على بيانات الهاتف عند غياب Wi-Fi."));
        final CheckBox wifi = new CheckBox(this);
        wifi.setText(text("Download over Wi-Fi only", "التنزيل عبر Wi-Fi فقط"));
        wifi.setTextColor(Ui.TEXT);
        wifi.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        wifi.setMinHeight(Ui.dp(this, 52));
        wifi.setChecked(DownloadService.wifiOnly(this));
        wifi.setOnCheckedChangeListener((button, checked) ->
                DownloadService.setWifiOnly(this, checked));
        root.addView(wifi, row());

        addLabel(root, text("STORAGE & RECOVERY", "التخزين والاستعادة"),
                text("Check disk space and find safe-to-remove temporary files.",
                        "افحص المساحة والملفات المؤقتة الآمنة للتنظيف."));
        final android.widget.Button storage = Ui.button(this,
                text("Open Storage & Recovery", "فتح التخزين والاستعادة"), false);
        storage.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, StorageActivity.class)));
        root.addView(storage, new LinearLayout.LayoutParams(-1, Ui.dp(this, 54)));

        final TextView note = Ui.sans(this, 12, Ui.MUTED);
        note.setText(text("Default qualities affect the order of offered formats, not "
                        + "the availability of a stream. You can still select any offered format.",
                "الجودة المفضلة تغيّر ترتيب الخيارات فقط ولا تضمن توفر أي صيغة. "
                        + "يمكنك دائماً اختيار أي جودة متاحة."));
        note.setPadding(0, Ui.dp(this, 22), 0, 0);
        root.addView(note);
        setContentView(scroll);
    }

    private void addLabel(LinearLayout root, String title, String detail) {
        final TextView label = Ui.eyebrow(this, title);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 19);
        root.addView(label, lp);
        final TextView description = Ui.sans(this, 12, Ui.MUTED);
        description.setText(detail);
        description.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        root.addView(description);
    }

    private Spinner spinner(String[] labels) {
        final Spinner spinner = new Spinner(this);
        final ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(Ui.dp(this, 48));
        spinner.setBackground(Ui.box(this, Ui.SURFACE_RAISED, Ui.LINE, 14));
        return spinner;
    }

    private LinearLayout.LayoutParams row() {
        return new LinearLayout.LayoutParams(-1, Ui.dp(this, 50));
    }

    private static int indexOf(int[] options, int value) {
        for (int i = 0; i < options.length; i++) if (options[i] == value) return i;
        return 0;
    }

    private static int indexOf(String[] options, String value) {
        for (int i = 0; i < options.length; i++)
            if (options[i].equals(value)) return i;
        return 0;
    }

    private abstract static class Selected implements AdapterView.OnItemSelectedListener {
        @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            selected(position);
        }
        @Override public void onNothingSelected(AdapterView<?> parent) {}
        public abstract void selected(int position);
    }
}
