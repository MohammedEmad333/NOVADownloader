package com.example.ytdl;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * User-controlled storage diagnostics. Never silently deletes cache data:
 * inspection is read-only and cleanup requires a confirmation.
 */
public final class StorageActivity extends Activity {
    private TextView report;
    private Button cleanup;
    private Button refresh;
    private volatile boolean busy;

    private String tr(String english, String arabic) {
        return NovaPreferences.text(this, english, arabic);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.applyChrome(this);
        buildScreen();
        inspect();
    }

    private void buildScreen() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(NovaPreferences.arabic(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 19),
                Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root);

        final Button back = Ui.button(this, tr("←  Settings", "الإعدادات  →"), false);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)));

        final LinearLayout heading = Ui.section(this,
                tr("NOVA / STORAGE", "نوفا / التخزين"),
                tr("Storage & Recovery", "التخزين والاستعادة"),
                tr("Check available space and safely remove stale temporary files.",
                        "افحص المساحة ونظّف الملفات المؤقتة المهجورة بأمان."));
        final LinearLayout.LayoutParams headParams =
                new LinearLayout.LayoutParams(-1, -2);
        headParams.topMargin = Ui.dp(this, 23);
        headParams.bottomMargin = Ui.dp(this, 16);
        root.addView(heading, headParams);

        report = Ui.sans(this, 14, Ui.TEXT);
        report.setBackground(Ui.hero(this));
        report.setMinHeight(Ui.dp(this, 160));
        report.setPadding(Ui.dp(this, 16), Ui.dp(this, 18),
                Ui.dp(this, 16), Ui.dp(this, 18));
        report.setText(tr("Checking storage…", "جاري فحص التخزين…"));
        root.addView(report, new LinearLayout.LayoutParams(-1, -2));

        refresh = Ui.button(this, tr("Refresh scan", "إعادة الفحص"), false);
        refresh.setOnClickListener(v -> inspect());
        final LinearLayout.LayoutParams refreshParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        refreshParams.topMargin = Ui.dp(this, 16);
        root.addView(refresh, refreshParams);

        cleanup = Ui.button(this, tr("Clean old temporary files", "تنظيف الملفات المؤقتة القديمة"), true);
        cleanup.setEnabled(false);
        cleanup.setOnClickListener(v -> confirmCleanup());
        final LinearLayout.LayoutParams cleanParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        cleanParams.topMargin = Ui.dp(this, 10);
        root.addView(cleanup, cleanParams);

        final TextView note = Ui.sans(this, 12, Ui.MUTED);
        note.setText(tr("NOVA only cleans recognized task cache files older than 24 hours "
                        + "when no transfer is running. Paused and queued downloads are protected. "
                        + "Saved media and unrelated cache files are never touched.",
                "يحذف NOVA فقط ملفات المهام المؤقتة المعروفة التي مر عليها ٢٤ ساعة "
                        + "عند عدم وجود تنزيل نشط. التنزيلات المتوقفة والمنتظرة محمية، "
                        + "ولا يتعرض للوسائط المحفوظة أو ملفات التطبيقات الأخرى."));
        note.setPadding(0, Ui.dp(this, 17), 0, 0);
        root.addView(note);
        setContentView(scroll);
    }

    private void lockButtons(boolean inProgress) {
        busy = inProgress;
        refresh.setEnabled(!inProgress);
        cleanup.setEnabled(false);
    }

    private void inspect() {
        if (busy) return;
        lockButtons(true);
        report.setText(tr("Checking storage…", "جاري فحص التخزين…"));
        new Thread(() -> {
            try {
                final StorageHealth.Report data = StorageHealth.inspect(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    lockButtons(false);
                    report.setText(format(data));
                    cleanup.setEnabled(data.reclaimableFiles > 0 && !data.workerRunning);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    lockButtons(false);
                    report.setText(tr("Storage scan could not be completed.",
                            "تعذر إكمال فحص التخزين."));
                });
            }
        }, "nova-storage-inspect").start();
    }

    private String format(StorageHealth.Report data) {
        final String free = data.availableBytes < 0 ? tr("unknown", "غير معروف")
                : Ui.bytes(data.availableBytes);
        return tr("Free space: ", "المساحة المتاحة: ") + free
                + "\n" + tr("Reclaimable temporary files: ", "ملفات مؤقتة يمكن تنظيفها: ")
                + data.reclaimableFiles + " · " + Ui.bytes(data.reclaimableBytes)
                + "\n" + tr("Protected partial files: ", "ملفات جزئية محمية: ")
                + data.protectedFiles + " · " + Ui.bytes(data.protectedBytes)
                + "\n" + tr("Paused downloads: ", "تنزيلات متوقفة: ") + data.pausedTasks
                + "\n" + tr("Waiting downloads: ", "تنزيلات منتظرة: ") + data.waitingTasks
                + "\n" + (data.workerRunning
                ? tr("Transfer active: cleanup is disabled.",
                    "يوجد تنزيل نشط: التنظيف معطّل.")
                : tr("No transfer running.", "لا يوجد تنزيل نشط."));
    }

    private void confirmCleanup() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle(tr("Clean old temporary files?",
                        "هل تريد تنظيف الملفات المؤقتة القديمة؟"))
                .setMessage(tr("Only unreferenced task files at least 24 hours old "
                                + "will be deleted. Paused downloads and saved media stay safe.",
                        "سيتم حذف ملفات المهام غير المستخدمة الأقدم من ٢٤ ساعة فقط. "
                                + "تبقى التنزيلات المتوقفة والملفات المحفوظة دون تغيير."))
                .setPositiveButton(tr("Clean", "تنظيف"), (dialog, which) -> clean())
                .setNegativeButton(tr("Cancel", "إلغاء"), null)
                .show();
    }

    private void clean() {
        if (busy) return;
        lockButtons(true);
        new Thread(() -> {
            try {
                final StorageHealth.Cleanup result = StorageHealth.cleanup(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    lockButtons(false);
                    final String message = result.blockedByTransfer
                            ? tr("A transfer is running. Nothing else was deleted.",
                                 "هناك تنزيل نشط. لم تكتمل عملية التنظيف.")
                            : tr("Removed ", "تم حذف ") + result.filesRemoved
                            + tr(" files (", " ملفات (") + Ui.bytes(result.bytesFreed) + ")";
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    inspect();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    lockButtons(false);
                    Toast.makeText(this,
                            tr("Cleanup failed; no other files were selected.",
                                    "فشل التنظيف، ولم تُحدّد ملفات أخرى."),
                            Toast.LENGTH_LONG).show();
                    inspect();
                });
            }
        }, "nova-storage-cleanup").start();
    }
}
