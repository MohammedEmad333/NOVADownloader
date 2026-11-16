package com.example.ytdl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.GridView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;
import android.app.AlertDialog;
import android.text.InputFilter;
import android.content.DialogInterface;
import java.util.HashSet;
import java.util.Arrays;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** NOVA library for completed and interrupted media downloads. */
public final class HistoryActivity extends Activity {
    static final String EXTRA_START_IMPORT = "nova_start_video_import";
    private static final int PICK_VIDEO = 7301;
    private boolean importing;
    private final List<DownloadService.Task> items = new ArrayList<>();
    private HistoryAdapter adapter;
    private TextView summary;
    private EditText search;
    private Spinner category;
    private Spinner sort;
    private Spinner source;
    private ListView list;
    private GridView grid;
    private LinearLayout advancedFilters;
    private Button advancedButton;
    private Button viewButton;
    private final List<TextView> quickFilters = new ArrayList<>();
    private boolean gridMode;
    private final LibrarySelection selection = new LibrarySelection();
    private List<LibraryCollections.Collection> collections = Collections.emptyList();
    private String activeCollectionId;
    private Spinner collectionsFilter;
    private Button manageCollections;
    private Button selectionToggle;
    private LinearLayout batchControls;
    private TextView batchSummary;
    private boolean selectionMode;
    private boolean rebuildingCollectionFilter;
    private LibrarySnapshot catalog = LibrarySnapshot.EMPTY;
    private Set<Long> possibleDuplicates = Collections.emptySet();
    private Map<String, LibraryFileStatus.State> fileStatuses = Collections.emptyMap();
    private Thread fileScan;
    private int fileScanGeneration;
    private boolean restoringView;

    private String tr(String english, String arabic) {
        return NovaPreferences.text(this, english, arabic);
    }

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);
        Ui.applyChrome(this);
        DownloadService.ensureLoaded(this);
        setContentView(buildUi());
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_START_IMPORT, false)) {
            getIntent().removeExtra(EXTRA_START_IMPORT);
            openVideoPicker();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        stopFileScan();
        super.onPause();
    }

    private void stopFileScan() {
        fileScanGeneration++;
        if (fileScan != null) fileScan.interrupt();
        fileScan = null;
    }

    private void verifyFilesInBackground() {
        stopFileScan();
        fileStatuses = Collections.emptyMap();
        final int generation = fileScanGeneration;
        final List<DownloadService.Task> snapshot = catalog.tasks;
        fileScan = new Thread(() -> {
            final Map<String, LibraryFileStatus.State> result = LibraryFileStatus.scan(
                    snapshot, uri -> LibraryFileStatus.check(getContentResolver(), uri));
            if (Thread.currentThread().isInterrupted()) return;
            runOnUiThread(() -> {
                if (generation != fileScanGeneration || isFinishing() || isDestroyed()) return;
                fileStatuses = result;
                fileScan = null;
                selection.retainValid(catalog.tasks, fileStatuses);
                renderLibrary();
            });
        }, "nova-library-file-check");
        fileScan.setDaemon(true);
        fileScan.start();
    }

    private View buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(NovaPreferences.arabic(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 18),
                Ui.dp(this, 18), Ui.dp(this, 10));

        final LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);

        final TextView back = Ui.headline(this, 25);
        back.setText("←");
        back.setTextColor(Ui.ACCENT);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(tr("Back to Explore and Transfers", "العودة للبحث والتنزيلات"));
        back.setBackground(Ui.pressable(this, Ui.SURFACE, Ui.ACCENT_SOFT, 15));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(
                Ui.dp(this, 48), Ui.dp(this, 48)));

        final TextView brand = Ui.eyebrow(this, tr("NOVA / YOUR LIBRARY", "نوفا / مكتبتك"));
        final LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1f);
        brandParams.leftMargin = Ui.dp(this, 13);
        top.addView(brand, brandParams);
        root.addView(top);

        final LinearLayout heading = Ui.section(this,
                tr("LIBRARY / 03", "المكتبة / ٠٣"),
                tr("Your collection", "مكتبتك"),
                tr("Everything you've saved, with the option to open or share.",
                        "كل ما حفظته في مكان واحد، جاهز للتشغيل والمشاركة."));
        final LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = Ui.dp(this, 18);
        headingParams.bottomMargin = Ui.dp(this, 18);
        root.addView(heading, headingParams);

        summary = Ui.mono(this, 13, Ui.TEXT);
        summary.setBackground(Ui.hero(this));
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(Ui.dp(this, 17), Ui.dp(this, 15),
                Ui.dp(this, 17), Ui.dp(this, 15));
        summary.setMinHeight(Ui.dp(this, 65));
        final LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.bottomMargin = Ui.dp(this, 14);
        root.addView(summary, summaryParams);

        final android.widget.Button importVideo = Ui.button(this,
                tr("＋ Import a video from your device",
                        "＋ استيراد فيديو من جهازك"), true);
        importVideo.setContentDescription(tr("Import a saved local video",
                "استيراد فيديو محفوظ على الجهاز"));
        importVideo.setOnClickListener(v -> openVideoPicker());
        final LinearLayout.LayoutParams importParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        importParams.bottomMargin = Ui.dp(this, 12);
        root.addView(importVideo, importParams);

        final LibraryViewState.State savedView = LibraryViewState.load(this);
        restoringView = true;
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTED);
        search.setTextSize(Ui.size(14));
        search.setHint(tr("Search saved files, titles or artists",
                "ابحث في الملفات أو العناوين أو الفنانين"));
        search.setBackground(Ui.box(this, Ui.SURFACE_RAISED, Ui.LINE, 15));
        search.setPadding(Ui.dp(this, 13), 0, Ui.dp(this, 13), 0);
        final LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                -1, Ui.dp(this, 52));
        searchParams.bottomMargin = Ui.dp(this, 10);
        root.addView(search, searchParams);
        search.setText(savedView.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                updateView();
            }
            @Override public void afterTextChanged(Editable value) {}
        });

        gridMode = savedView.grid;
        final LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        final android.widget.HorizontalScrollView quickScroll =
                new android.widget.HorizontalScrollView(this);
        quickScroll.setHorizontalScrollBarEnabled(false);
        quickScroll.addView(quick);
        final int[] quickCategories = {0, 1, 2, 4, 5};
        final String[] quickLabels = {
                tr("All", "الكل"), tr("Video", "فيديو"),
                tr("Audio", "صوت"), tr("Attention", "متابعة"),
                tr("Favorites", "المفضلة")
        };
        for (int i = 0; i < quickCategories.length; i++) {
            final int selected = quickCategories[i];
            final TextView chip = Ui.chip(this, quickLabels[i], Ui.TEXT);
            chip.setMinHeight(Ui.dp(this, 48));
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> category.setSelection(selected));
            quick.addView(chip);
            quickFilters.add(chip);
        }
        final LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(-1, -2);
        quickParams.bottomMargin = Ui.dp(this, 7);
        root.addView(quickScroll, quickParams);

        final LinearLayout viewControls = new LinearLayout(this);
        viewControls.setOrientation(LinearLayout.HORIZONTAL);
        advancedButton = Ui.button(this, tr("More filters", "فلاتر إضافية"), false);
        advancedButton.setContentDescription(tr("Show or hide advanced Library filters",
                "عرض أو إخفاء فلاتر المكتبة الإضافية"));
        viewControls.addView(advancedButton,
                new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        viewButton = Ui.button(this, tr("Grid view", "عرض شبكي"), false);
        final LinearLayout.LayoutParams viewParams =
                new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f);
        viewParams.leftMargin = Ui.dp(this, 7);
        viewControls.addView(viewButton, viewParams);
        root.addView(viewControls, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));

        advancedFilters = new LinearLayout(this);
        advancedFilters.setOrientation(LinearLayout.VERTICAL);
        advancedFilters.setPadding(0, Ui.dp(this, 9), 0, 0);
        root.addView(advancedFilters);
        advancedFilters.setVisibility(LibraryPresentation.expandAdvancedFilters(
                savedView.category, savedView.sort, savedView.source)
                ? View.VISIBLE : View.GONE);
        advancedButton.setOnClickListener(v -> {
            advancedFilters.setVisibility(advancedFilters.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
            updateAdvancedButton();
        });
        viewButton.setOnClickListener(v -> {
            gridMode = !gridMode;
            updateView();
        });
        updateAdvancedButton();

        final LinearLayout collectionRow = new LinearLayout(this);
        collectionRow.setOrientation(LinearLayout.HORIZONTAL);
        collectionsFilter = selector(new String[]{tr("All collections", "كل المجموعات")});
        collectionRow.addView(collectionsFilter,
                new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        manageCollections = Ui.button(this, tr("Manage", "إدارة"), false);
        final LinearLayout.LayoutParams manageParams =
                new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
        manageParams.leftMargin = Ui.dp(this, 7);
        collectionRow.addView(manageCollections, manageParams);
        final LinearLayout.LayoutParams collectionParams =
                new LinearLayout.LayoutParams(-1, -2);
        collectionParams.topMargin = Ui.dp(this, 10);
        collectionParams.bottomMargin = Ui.dp(this, 10);
        root.addView(collectionRow, collectionParams);
        manageCollections.setOnClickListener(v -> showCollectionManager());
        collectionsFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                if (rebuildingCollectionFilter) return;
                activeCollectionId = position <= 0 || position > collections.size()
                        ? null : collections.get(position - 1).id;
                renderLibrary();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        selectionToggle = Ui.button(this, tr("Select files", "تحديد ملفات"), false);
        selectionToggle.setContentDescription(tr("Select saved files for safe batch actions",
                "تحديد الملفات المحفوظة لإجراءات جماعية آمنة"));
        selectionToggle.setOnClickListener(v -> {
            selectionMode = !selectionMode;
            if (!selectionMode) selection.clear();
            updateBatchControls();
            renderLibrary();
        });
        root.addView(selectionToggle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        batchControls = new LinearLayout(this);
        batchControls.setOrientation(LinearLayout.VERTICAL);
        batchControls.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 9));
        batchSummary = Ui.sans(this, 13, Ui.TEXT);
        batchSummary.setMinHeight(Ui.dp(this, 32));
        batchControls.addView(batchSummary);
        final LinearLayout batchRow = new LinearLayout(this);
        batchRow.setOrientation(LinearLayout.HORIZONTAL);
        addBatchButton(batchRow, tr("Select visible", "حدد الظاهر"), v -> selectVisible());
        addBatchButton(batchRow, tr("Favorite", "المفضلة"), v -> favoriteSelection());
        batchControls.addView(batchRow);
        final LinearLayout batchRow2 = new LinearLayout(this);
        batchRow2.setOrientation(LinearLayout.HORIZONTAL);
        addBatchButton(batchRow2, tr("Collections", "المجموعات"),
                v -> chooseCollectionForSelection());
        addBatchButton(batchRow2, tr("Share", "مشاركة"), v -> shareSelection());
        batchControls.addView(batchRow2);
        root.addView(batchControls);
        updateBatchControls();
        restoreCollections(null);

        final LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        category = selector(new String[]{
                tr("All files", "الكل"), tr("Videos", "فيديو"),
                tr("Audio", "صوت"), tr("Subtitles", "ترجمة"),
                tr("Needs attention", "تحتاج متابعة"),
                tr("Favorites", "المفضلة"),
                tr("Possible duplicates", "تكرارات محتملة")
        });
        sort = selector(new String[]{
                tr("Newest first", "الأحدث أولاً"), tr("Oldest first", "الأقدم أولاً"),
                tr("By title", "حسب العنوان"),
                tr("Group by source", "تجميع حسب المصدر")
        });
        tools.addView(category, new LinearLayout.LayoutParams(0, Ui.dp(this, 49), 1.1f));
        final LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 49), 1f);
        sortParams.leftMargin = Ui.dp(this, 7);
        tools.addView(sort, sortParams);
        final LinearLayout.LayoutParams toolParams = new LinearLayout.LayoutParams(-1, -2);
        toolParams.bottomMargin = Ui.dp(this, 12);
        advancedFilters.addView(tools, toolParams);

        source = selector(new String[]{
                tr("Every source", "جميع المصادر"), tr("YouTube", "يوتيوب"),
                tr("SoundCloud", "ساوندكلاود"), tr("X / Twitter", "إكس / تويتر"),
                tr("Spotify matches", "مطابقات سبوتيفاي"),
                tr("Local imports", "ملفات مستوردة"), tr("Other", "أخرى")
        });
        final LinearLayout.LayoutParams sourceParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 49));
        sourceParams.bottomMargin = Ui.dp(this, 12);
        advancedFilters.addView(source, sourceParams);

        category.setSelection(savedView.category);
        sort.setSelection(savedView.sort);
        source.setSelection(savedView.source);
        restoringView = false;
        final AdapterView.OnItemSelectedListener changed = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) { updateView(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        category.setOnItemSelectedListener(changed);
        sort.setOnItemSelectedListener(changed);
        source.setOnItemSelectedListener(changed);

        list = new ListView(this);
        adapter = new HistoryAdapter();
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 12));
        final AdapterView.OnItemClickListener openEntry = (parent, view, position, id) -> {
            if (position >= items.size()) return;
            final DownloadService.Task task = items.get(position);
            if (selectionMode) {
                if (!selection.toggle(task, fileStatuses.get(task.outputUri))) {
                    Toast.makeText(this, tr("Choose a saved, available file (limit 100)",
                            "اختر ملفًا محفوظًا ومتاحًا (بحد أقصى 100)"),
                            Toast.LENGTH_SHORT).show();
                }
                updateBatchControls();
                renderLibrary();
                return;
            }
            if (task.state == DownloadService.State.FAILED
                    || task.state == DownloadService.State.CANCELLED) {
                DownloadService.retry(this, task);
                Toast.makeText(this, tr("Added back to Transfers", "أضيف إلى التنزيلات مجددًا"),
                        Toast.LENGTH_SHORT).show();
                finish();
            } else if (LibraryFileActions.eligible(task)) {
                if (fileUnavailable(task)) {
                    showUnavailableFile(task);
                } else if (LocalMediaPlayerActivity.canPlay(task)) {
                    playSaved(task);
                } else {
                    openSaved(task);
                }
            }
        };
        list.setOnItemClickListener(openEntry);
        grid = new GridView(this);
        grid.setNumColumns(LibraryPresentation.gridColumns(
                getResources().getConfiguration().screenWidthDp));
        grid.setHorizontalSpacing(Ui.dp(this, 9));
        grid.setVerticalSpacing(Ui.dp(this, 9));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setAdapter(adapter);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setBackgroundColor(Color.TRANSPARENT);
        grid.setOnItemClickListener(openEntry);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        final TextView explore = NovaNavigation.tab(this, tr("✦  Explore", "✦  استكشف"),
                false, v -> startActivity(NovaNavigation.mainIntent(this, NovaNavigation.EXPLORE)));
        final TextView transfers = NovaNavigation.tab(this, tr("↓  Transfers", "↓  التنزيلات"),
                false, v -> startActivity(NovaNavigation.mainIntent(this, NovaNavigation.TRANSFERS)));
        final TextView library = NovaNavigation.tab(this, tr("▣  Library", "▣  المكتبة"),
                true, v -> {});
        library.setClickable(false);
        library.setContentDescription(tr("Library, current destination",
                "المكتبة، القسم الحالي"));
        root.addView(NovaNavigation.bar(this, explore, transfers, library));
        updateLayoutControls();
        return root;
    }

    private void updateAdvancedButton() {
        if (advancedButton == null || advancedFilters == null) return;
        advancedButton.setText(advancedFilters.getVisibility() == View.VISIBLE
                ? tr("Fewer filters", "إخفاء الفلاتر")
                : tr("More filters", "فلاتر إضافية"));
    }

    private void updateLayoutControls() {
        if (list != null) list.setVisibility(gridMode ? View.GONE : View.VISIBLE);
        if (grid != null) grid.setVisibility(gridMode ? View.VISIBLE : View.GONE);
        if (viewButton != null) {
            viewButton.setText(gridMode ? tr("List view", "عرض قائمة")
                    : tr("Grid view", "عرض شبكي"));
            viewButton.setContentDescription(gridMode
                    ? tr("Switch to list view", "التبديل إلى عرض القائمة")
                    : tr("Switch to grid view", "التبديل إلى العرض الشبكي"));
        }
        final int[] categoryIds = {0, 1, 2, 4, 5};
        final int selected = category == null ? 0 : category.getSelectedItemPosition();
        for (int i = 0; i < quickFilters.size(); i++) {
            final boolean active = selected == categoryIds[i];
            final TextView chip = quickFilters.get(i);
            chip.setBackground(Ui.pressable(this,
                    active ? Ui.ACCENT : Ui.SURFACE_RAISED, Ui.ACCENT_SOFT, 14));
            chip.setTextColor(active ? Ui.BG : Ui.TEXT);
            chip.setSelected(active);
        }
    }

    private void openVideoPicker() {
        if (importing) return;
        final Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("video/*");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(picker, PICK_VIDEO);
        } catch (android.content.ActivityNotFoundException unavailable) {
            Toast.makeText(this, tr("No file picker installed",
                    "لا يوجد تطبيق لاختيار الملفات"), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_VIDEO || resultCode != RESULT_OK
                || data == null || data.getData() == null || importing) return;
        importing = true;
        final Uri chosen = data.getData();
        Toast.makeText(this, tr("Importing selected video…", "جاري استيراد الفيديو…"),
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            android.net.Uri importedUri = null;
            try {
                final LocalVideoImport.Imported imported = LocalVideoImport.copy(this, chosen);
                importedUri = imported.uri;
                DownloadService.registerImported(this, imported);
                runOnUiThread(() -> {
                    importing = false;
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, tr("Video imported into Library",
                            "تم استيراد الفيديو إلى المكتبة"), Toast.LENGTH_LONG).show();
                    refresh();
                });
            } catch (Exception error) {
                // If queue persistence fails after publishing, avoid an untracked
                // duplicate in Movies/NOVA.
                if (importedUri != null) {
                    try { getContentResolver().delete(importedUri, null, null); }
                    catch (RuntimeException ignored) {}
                }
                final String message = error.getMessage();
                runOnUiThread(() -> {
                    importing = false;
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this,
                            tr("Video import failed: ", "تعذر استيراد الفيديو: ")
                                    + (message == null ? "" : message),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "nova-video-import").start();
    }

    private Spinner selector(String[] labels) {
        final Spinner control = new Spinner(this);
        final ArrayAdapter<String> values = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        values.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        control.setAdapter(values);
        control.setBackground(Ui.box(this, Ui.SURFACE_RAISED, Ui.LINE, 14));
        return control;
    }

    private boolean fileUnavailable(DownloadService.Task task) {
        return task != null && LibraryFileStatus.unavailable(fileStatuses.get(task.outputUri));
    }

    private void showUnavailableFile(DownloadService.Task task) {
        final LibraryFileStatus.State state = fileStatuses.get(task.outputUri);
        Toast.makeText(this, state == LibraryFileStatus.State.NO_ACCESS
                        ? tr("NOVA cannot access this saved file. Check its permissions.",
                                "لا يستطيع نوفا الوصول إلى هذا الملف. تحقق من أذونات الوصول.")
                        : tr("This saved file is missing. The Library record is kept.",
                                "هذا الملف المحفوظ مفقود. تم الاحتفاظ بسجله في المكتبة."),
                Toast.LENGTH_LONG).show();
    }

    private void updateView() {
        if (restoringView) return;
        LibraryViewState.save(this,
                search == null ? "" : search.getText().toString(),
                category == null ? 0 : category.getSelectedItemPosition(),
                sort == null ? 0 : sort.getSelectedItemPosition(),
                source == null ? 0 : source.getSelectedItemPosition(), gridMode);
        updateLayoutControls();
        renderLibrary();
    }

    private void refresh() {
        // Only queue updates and lifecycle resumes rebuild the snapshot or scan
        // MediaStore. Typing and changing selectors reuse cached metadata.
        catalog = LibrarySnapshot.capture(DownloadService.snapshot());
        possibleDuplicates = catalog.possibleDuplicates;
        selection.retainValid(catalog.tasks, fileStatuses);
        restoreCollections(activeCollectionId);
        renderLibrary();
        verifyFilesInBackground();
    }

    private void renderLibrary() {
        final int selectedCategory = category == null ? 0 : category.getSelectedItemPosition();
        final int selectedSource = source == null ? 0 : source.getSelectedItemPosition();
        final int selectedSort = sort == null ? 0 : sort.getSelectedItemPosition();
        final String query = search == null ? "" : search.getText().toString();
        final LibraryCollections.Collection active =
                LibraryCollections.byId(collections, activeCollectionId);
        final List<DownloadService.Task> filtered = LibraryFilter.select(
                catalog.tasks, query,
                selectedCategory == 6 ? LibraryFilter.ALL : selectedCategory,
                selectedSort, task -> LibraryFavorites.isFavorite(this, task),
                selectedSource, selectedCategory == 6 ? possibleDuplicates : null);
        items.clear();
        for (DownloadService.Task task : filtered) {
            if (active == null || active.contains(task)) items.add(task);
        }
        if (summary != null) {
            int missing = 0;
            int denied = 0;
            for (DownloadService.Task task : catalog.tasks) {
                if (task.state != DownloadService.State.DONE) continue;
                final LibraryFileStatus.State status = fileStatuses.get(task.outputUri);
                if (status == LibraryFileStatus.State.MISSING) missing++;
                else if (status == LibraryFileStatus.State.NO_ACCESS) denied++;
            }
            final String alertsEn = missing + " missing · " + denied + " inaccessible";
            final String alertsAr = missing + " مفقودة · " + denied + " غير متاحة";
            summary.setText(tr("✦  " + catalog.saved + " saved    ·    " + catalog.failed
                            + " need attention\n" + items.size() + " visible · "
                            + possibleDuplicates.size() + " possible copies\n" + alertsEn,
                    "✦  " + catalog.saved + " محفوظ    ·    " + catalog.failed
                            + " تحتاج متابعة\n" + items.size() + " ظاهرة · "
                            + possibleDuplicates.size() + " نسخ محتملة\n" + alertsAr));
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateLayoutControls();
        updateBatchControls();
    }

    private void addBatchButton(LinearLayout row, String label,
                                View.OnClickListener listener) {
        final Button button = Ui.button(this, label, false);
        button.setTextSize(Ui.size(12));
        button.setOnClickListener(listener);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f);
        params.rightMargin = Ui.dp(this, 5);
        row.addView(button, params);
    }

    private void updateBatchControls() {
        if (batchControls != null) batchControls.setVisibility(
                selectionMode ? View.VISIBLE : View.GONE);
        if (selectionToggle != null) selectionToggle.setText(selectionMode
                ? tr("Done selecting", "إنهاء التحديد")
                : tr("Select files", "تحديد ملفات"));
        if (batchSummary != null) batchSummary.setText(tr(
                selection.size() + " selected · files are never deleted",
                selection.size() + " محددة · لن يتم حذف الملفات"));
    }

    private List<DownloadService.Task> selectedFiles() {
        return selection.resolve(catalog.tasks, fileStatuses);
    }

    private void selectVisible() {
        int count = 0;
        for (DownloadService.Task task : items) {
            if (selection.size() >= LibrarySelection.MAX_SELECTED) break;
            if (!selection.contains(task)
                    && selection.toggle(task, fileStatuses.get(task.outputUri))) count++;
        }
        updateBatchControls();
        renderLibrary();
        Toast.makeText(this, tr(count + " added to selection",
                "تم تحديد " + count + " ملف إضافي"), Toast.LENGTH_SHORT).show();
    }

    private void favoriteSelection() {
        final List<DownloadService.Task> files = selectedFiles();
        if (files.isEmpty()) { showSelectionRequired(); return; }
        int changed = 0;
        for (DownloadService.Task task : files) {
            if (!LibraryFavorites.isFavorite(this, task)) {
                LibraryFavorites.toggle(this, task);
                changed++;
            }
        }
        renderLibrary();
        Toast.makeText(this, tr(changed + " added to Favorites",
                "أضيف " + changed + " إلى المفضلة"), Toast.LENGTH_SHORT).show();
    }

    private void shareSelection() {
        final List<DownloadService.Task> files = selectedFiles();
        if (files.isEmpty()) { showSelectionRequired(); return; }
        final Intent intent = LibraryBatchActions.shareIntent(files);
        if (intent == null) {
            Toast.makeText(this, tr("Select 1–50 valid files to share",
                    "حدد من ملف واحد إلى 50 ملفًا صالحًا للمشاركة"), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(Intent.createChooser(intent, tr("Share saved media",
                    "مشاركة الملفات المحفوظة")));
        } catch (RuntimeException error) {
            Toast.makeText(this, tr("No app can share these files",
                    "لا يوجد تطبيق متاح لمشاركة هذه الملفات"), Toast.LENGTH_LONG).show();
        }
    }

    private void showSelectionRequired() {
        Toast.makeText(this, tr("Select saved files first", "حدد ملفات محفوظة أولًا"),
                Toast.LENGTH_SHORT).show();
    }

    private void restoreCollections(String preferredId) {
        try {
            collections = LibraryCollections.list(this);
        } catch (IllegalStateException failure) {
            collections = Collections.emptyList();
            Toast.makeText(this, tr("Collection labels cannot be read; no files changed",
                    "تعذرت قراءة المجموعات؛ لم يتم تعديل أي ملف"), Toast.LENGTH_LONG).show();
        }
        if (collectionsFilter == null) return;
        final String[] labels = new String[collections.size() + 1];
        labels[0] = tr("All collections", "كل المجموعات");
        int chosen = 0;
        for (int i = 0; i < collections.size(); i++) {
            final LibraryCollections.Collection item = collections.get(i);
            labels[i + 1] = item.name + " (" + item.count() + ")";
            if (item.id.equals(preferredId)) chosen = i + 1;
        }
        rebuildingCollectionFilter = true;
        collectionsFilter.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        collectionsFilter.setSelection(chosen);
        activeCollectionId = chosen == 0 ? null : collections.get(chosen - 1).id;
        rebuildingCollectionFilter = false;
    }

    private void showCollectionManager() {
        final String[] options = {tr("Create collection", "إنشاء مجموعة"),
                tr("Rename collection", "إعادة تسمية مجموعة"),
                tr("Remove collection label (files kept)",
                        "إزالة المجموعة فقط (الملفات محفوظة)")};
        new AlertDialog.Builder(this).setTitle(tr("Manage collections", "إدارة المجموعات"))
                .setItems(options, (dialog, position) -> {
                    if (position == 0) promptCollectionName(null);
                    else chooseCollection(position == 1);
                })
                .setNegativeButton(tr("Close", "إغلاق"), null).show();
    }

    private void chooseCollection(boolean rename) {
        if (collections.isEmpty()) {
            Toast.makeText(this, tr("Create a collection first", "أنشئ مجموعة أولًا"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = new String[collections.size()];
        for (int i = 0; i < collections.size(); i++) labels[i] = collections.get(i).name;
        new AlertDialog.Builder(this).setTitle(tr("Choose collection", "اختر مجموعة"))
                .setItems(labels, (dialog, index) -> {
                    final LibraryCollections.Collection item = collections.get(index);
                    if (rename) promptCollectionName(item);
                    else new AlertDialog.Builder(this)
                            .setTitle(tr("Remove collection label?", "إزالة المجموعة؟"))
                            .setMessage(tr("Only its organization label is removed. Saved media stays.",
                                    "سيُحذف اسم المجموعة فقط وتبقى الملفات محفوظة."))
                            .setPositiveButton(tr("Remove label", "إزالة المجموعة"),
                                    (confirm, which) -> {
                                        try {
                                            LibraryCollections.remove(this, item.id);
                                            restoreCollections(activeCollectionId);
                                            renderLibrary();
                                        } catch (RuntimeException error) {
                                            showCollectionError(error);
                                        }
                                    })
                            .setNegativeButton(tr("Keep", "إبقاء"), null).show();
                }).setNegativeButton(tr("Cancel", "إلغاء"), null).show();
    }

    private void promptCollectionName(LibraryCollections.Collection existing) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(
                LibraryCollections.MAX_NAME_LENGTH)});
        input.setHint(tr("Collection name", "اسم المجموعة"));
        if (existing != null) input.setText(existing.name);
        new AlertDialog.Builder(this).setTitle(existing == null
                ? tr("New collection", "مجموعة جديدة")
                : tr("Rename collection", "إعادة تسمية المجموعة"))
                .setView(input)
                .setPositiveButton(tr("Save", "حفظ"), (dialog, which) -> {
                    try {
                        if (existing == null) {
                            final LibraryCollections.Collection created =
                                    LibraryCollections.create(this, input.getText().toString());
                            restoreCollections(created.id);
                        } else {
                            LibraryCollections.rename(this, existing.id,
                                    input.getText().toString());
                            restoreCollections(existing.id);
                        }
                        renderLibrary();
                    } catch (RuntimeException error) { showCollectionError(error); }
                })
                .setNegativeButton(tr("Cancel", "إلغاء"), null).show();
    }

    private void chooseCollectionForSelection() {
        final List<DownloadService.Task> selected = selectedFiles();
        if (selected.isEmpty()) { showSelectionRequired(); return; }
        if (collections.isEmpty()) {
            Toast.makeText(this, tr("Create a collection in Manage first",
                    "أنشئ مجموعة من إدارة المجموعات أولًا"), Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = new String[collections.size()];
        for (int i = 0; i < collections.size(); i++) labels[i] = collections.get(i).name;
        new AlertDialog.Builder(this).setTitle(tr("Choose collection", "اختر مجموعة"))
                .setItems(labels, (dialog, index) -> {
                    final LibraryCollections.Collection chosen = collections.get(index);
                    new AlertDialog.Builder(this)
                            .setTitle(chosen.name)
                            .setItems(new String[]{tr("Add selected", "إضافة المحددة"),
                                    tr("Remove selected from collection",
                                            "إزالة المحددة من المجموعة")},
                                    (action, which) -> {
                                        try {
                                            final int count = which == 0
                                                    ? LibraryCollections.add(this, chosen.id, selected)
                                                    : LibraryCollections.removeMembers(this,
                                                            chosen.id, selected);
                                            restoreCollections(activeCollectionId);
                                            renderLibrary();
                                            Toast.makeText(this, tr(count + " memberships updated",
                                                    "تم تحديث " + count + " عنصرًا بالمجموعة"),
                                                    Toast.LENGTH_SHORT).show();
                                        } catch (RuntimeException error) {
                                            showCollectionError(error);
                                        }
                                    })
                            .setNegativeButton(tr("Cancel", "إلغاء"), null).show();
                }).setNegativeButton(tr("Cancel", "إلغاء"), null).show();
    }

    private void showCollectionError(RuntimeException error) {
        Toast.makeText(this, tr("Collection changes could not be saved: ",
                "تعذر حفظ تغييرات المجموعة: ") + error.getMessage(),
                Toast.LENGTH_LONG).show();
    }

    private TextView action(final String label, final int color, final View.OnClickListener click) {
        final TextView button = Ui.chip(this, label, color);
        button.setMinHeight(Ui.dp(this, 48));
        button.setMinWidth(Ui.dp(this, 48));
        button.setBackground(Ui.pressable(this, Ui.SURFACE_RAISED, Ui.ACCENT_SOFT, 14));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(click);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
        params.rightMargin = Ui.dp(this, 9);
        button.setLayoutParams(params);
        return button;
    }

    private void openSaved(final DownloadService.Task task) {
        if (fileUnavailable(task)) {
            showUnavailableFile(task);
            return;
        }
        final Intent intent = LibraryFileActions.viewIntent(task);
        if (intent == null) {
            Toast.makeText(this, tr("Saved file reference is unavailable",
                    "مرجع الملف المحفوظ غير متاح"), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(intent);
        } catch (final RuntimeException e) {
            Toast.makeText(this, tr("No app available to open this file",
                    "لا يوجد تطبيق متاح لفتح الملف"), Toast.LENGTH_SHORT).show();
        }
    }

    private void playSaved(final DownloadService.Task task) {
        if (fileUnavailable(task)) {
            showUnavailableFile(task);
            return;
        }
        if (!LocalMediaPlayerActivity.canPlay(task)) return;
        startActivity(LocalMediaPlayerActivity.intentFor(this, task));
    }

    private void toggleFavorite(final DownloadService.Task task) {
        LibraryFavorites.toggle(this, task);
        renderLibrary();
    }

    private String sourceLabel(final int sourceId) {
        switch (sourceId) {
            case LibraryOrganizer.YOUTUBE: return tr("YouTube", "يوتيوب");
            case LibraryOrganizer.SOUNDCLOUD: return tr("SoundCloud", "ساوندكلاود");
            case LibraryOrganizer.X: return tr("X / Twitter", "إكس / تويتر");
            case LibraryOrganizer.SPOTIFY: return tr("Spotify", "سبوتيفاي");
            case LibraryOrganizer.LOCAL: return tr("Local imports", "ملفات مستوردة");
            default: return tr("Other", "أخرى");
        }
    }

    private void reviewPossibleDuplicates(final DownloadService.Task task) {
        final List<DownloadService.Task> matching =
                LibraryOrganizer.matchingCopies(catalog.tasks, task);
        if (matching.size() < 2) {
            Toast.makeText(this, tr("No longer a duplicate candidate",
                    "لم يعد هذا الملف ضمن التكرارات المحتملة"), Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        final String[] labels = new String[matching.size()];
        for (int i = 0; i < matching.size(); i++) {
            final DownloadService.Task item = matching.get(i);
            final LibraryFileStatus.State availability = fileStatuses.get(item.outputUri);
            final String status = availability == LibraryFileStatus.State.PRESENT
                    ? tr("available", "متاح")
                    : availability == LibraryFileStatus.State.MISSING
                    ? tr("missing", "مفقود")
                    : availability == LibraryFileStatus.State.NO_ACCESS
                    ? tr("inaccessible", "غير متاح")
                    : tr("not checked", "لم يتم التحقق");
            labels[i] = (item.id == task.id ? tr("Current: ", "الحالي: ") : "")
                    + (item.title == null ? tr("Untitled", "بدون عنوان") : item.title)
                    + "\\n#" + item.id + " · " + item.formatLabel()
                    + " · " + sourceLabel(LibraryOrganizer.sourceOf(item))
                    + " · " + status;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(tr("Possible copies (metadata only, no deletion)",
                        "نسخ محتملة (حسب البيانات فقط، دون حذف)"))
                .setMessage(tr("Compare title, source, format and availability. "
                                + "These are only metadata matches, not verified identical bytes.",
                        "قارن العنوان والمصدر والصيغة والتوفر. التشابه بالبيانات فقط، "
                                + "ولا يعني تطابق الملفات بشكل مؤكد."))
                .setItems(labels, (dialog, index) -> {
                    final DownloadService.Task chosen = matching.get(index);
                    if (fileUnavailable(chosen)) {
                        showUnavailableFile(chosen);
                        return;
                    }
                    if (LocalMediaPlayerActivity.canPlay(chosen)) playSaved(chosen);
                    else if (chosen.outputUri != null) openSaved(chosen);
                })
                .setNegativeButton(tr("Close", "إغلاق"), null)
                .show();
    }

    private void shareSaved(final DownloadService.Task task) {
        if (fileUnavailable(task)) {
            showUnavailableFile(task);
            return;
        }
        final Intent intent = LibraryFileActions.shareIntent(task);
        if (intent == null) {
            Toast.makeText(this, tr("Saved file reference is unavailable",
                    "مرجع الملف المحفوظ غير متاح"), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(Intent.createChooser(intent, tr("Share download", "مشاركة الملف")));
        } catch (final RuntimeException e) {
            Toast.makeText(this, tr("Could not share this file",
                    "تعذرت مشاركة الملف"), Toast.LENGTH_SHORT).show();
        }
    }

    private final class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.max(1, items.size()); }
        @Override public Object getItem(final int p) {
            return p < items.size() ? items.get(p) : null;
        }
        @Override public long getItemId(final int p) { return p; }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            if (items.isEmpty()) {
                final LibraryEmptyState emptyState = LibraryEmptyState.forFilters(
                        catalog.finished,
                        search == null ? "" : search.getText().toString(),
                        category == null ? 0 : category.getSelectedItemPosition(),
                        source == null ? 0 : source.getSelectedItemPosition());
                final LinearLayout empty = Ui.section(HistoryActivity.this,
                        tr("YOUR SPACE", "مساحتك"),
                        tr(emptyState.englishTitle, emptyState.arabicTitle),
                        tr(emptyState.englishHint, emptyState.arabicHint));
                empty.setPadding(Ui.dp(HistoryActivity.this, 18),
                        Ui.dp(HistoryActivity.this, 26), Ui.dp(HistoryActivity.this, 18),
                        Ui.dp(HistoryActivity.this, 26));
                empty.setBackground(Ui.box(HistoryActivity.this, Ui.SURFACE, Ui.LINE, 20));
                return empty;
            }

            final DownloadService.Task task = items.get(position);
            final boolean chosen = selection.contains(task);
            final boolean compact = parent instanceof GridView
                    && LibraryPresentation.gridColumns(
                            getResources().getConfiguration().screenWidthDp) > 1;
            final LinearLayout card = new LinearLayout(HistoryActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.box(HistoryActivity.this,
                    chosen ? Ui.ACCENT_SOFT : Ui.SURFACE,
                    chosen ? Ui.ACCENT : Ui.LINE, 20));
            card.setPadding(Ui.dp(HistoryActivity.this, compact ? 11 : 16),
                    Ui.dp(HistoryActivity.this, compact ? 11 : 15),
                    Ui.dp(HistoryActivity.this, compact ? 11 : 16),
                    Ui.dp(HistoryActivity.this, compact ? 11 : 15));

            final LinearLayout heading = new LinearLayout(HistoryActivity.this);
            heading.setOrientation(LinearLayout.HORIZONTAL);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            final TextView title = Ui.headline(HistoryActivity.this, 15);
            title.setText((chosen ? "☑  " : selectionMode ? "□  " : "")
                    + (LibraryFavorites.isFavorite(HistoryActivity.this, task)
                    ? "★  " : "") + (task.title == null
                    ? tr("Untitled download", "تنزيل بلا عنوان") : task.title));
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

            final int color = task.state == DownloadService.State.DONE
                    ? Ui.OK : task.state == DownloadService.State.FAILED ? Ui.ERR : Ui.MUTED;
            final TextView state = Ui.chip(HistoryActivity.this, task.state.label, color);
            if (compact) {
                card.addView(heading);
                card.addView(state, new LinearLayout.LayoutParams(-1, -2));
            } else {
                final LinearLayout.LayoutParams badgeParams =
                        new LinearLayout.LayoutParams(-2, -2);
                badgeParams.leftMargin = Ui.dp(HistoryActivity.this, 9);
                heading.addView(state, badgeParams);
                card.addView(heading);
            }

            final boolean safeSaved = LibraryFileActions.eligible(task);
            final LibraryFileStatus.State availability = fileStatuses.get(task.outputUri);
            final boolean unavailable = LibraryFileStatus.unavailable(availability);
            final TextView detail = Ui.sans(HistoryActivity.this, 12, Ui.MUTED);
            detail.setText(task.formatLabel()
                    + (possibleDuplicates.contains(task.id)
                    ? tr(" · Possible duplicate, review manually",
                            " · تكرار محتمل، راجع الملف يدويًا") : "")
                    + (task.state == DownloadService.State.DONE && !safeSaved
                    ? tr(" · Invalid saved-file reference", " · مرجع الملف غير صالح") : "")
                    + (availability == LibraryFileStatus.State.MISSING
                    ? tr(" · File missing (record kept)", " · الملف مفقود (السجل محفوظ)") : "")
                    + (availability == LibraryFileStatus.State.NO_ACCESS
                    ? tr(" · File inaccessible (record kept)",
                            " · تعذر الوصول إلى الملف (السجل محفوظ)") : "")
                    + (task.state == DownloadService.State.FAILED
                    ? tr(" · Tap to retry", " · اضغط لإعادة المحاولة") : ""));
            final LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = Ui.dp(HistoryActivity.this, 8);
            card.addView(detail, detailParams);

            if (safeSaved && !unavailable && !selectionMode) {
                final LinearLayout actions = new LinearLayout(HistoryActivity.this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setPadding(0, Ui.dp(HistoryActivity.this, 12), 0, 0);
                if (LocalMediaPlayerActivity.canPlay(task)) {
                    actions.addView(action(tr("Play in NOVA", "تشغيل في نوفا"), Ui.OK,
                            v -> playSaved(task)));
                } else {
                    actions.addView(action(tr("Open file", "فتح"), Ui.OK,
                            v -> openSaved(task)));
                }
                if (LocalMediaPlayerActivity.canPlay(task)) {
                    actions.addView(action(tr("Open externally", "فتح خارجي"), Ui.MUTED,
                            v -> openSaved(task)));
                }
                if (possibleDuplicates.contains(task.id)) {
                    actions.addView(action(tr("Review copies", "مراجعة النسخ"), Ui.SECONDARY,
                            v -> reviewPossibleDuplicates(task)));
                }
                actions.addView(action(tr("Share", "مشاركة"), Ui.ACCENT,
                        v -> shareSaved(task)));
                final boolean starred = LibraryFavorites.isFavorite(HistoryActivity.this, task);
                actions.addView(action(starred ? tr("★ Saved", "★ مفضلة")
                                : tr("☆ Favorite", "☆ المفضلة"), Ui.SECONDARY,
                        v -> toggleFavorite(task)));
                final android.widget.HorizontalScrollView actionScroll =
                        new android.widget.HorizontalScrollView(HistoryActivity.this);
                actionScroll.setHorizontalScrollBarEnabled(false);
                actionScroll.setFillViewport(false);
                actionScroll.addView(actions);
                card.addView(actionScroll);
            }
            final LinearLayout wrap = new LinearLayout(HistoryActivity.this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(0, 0, 0, Ui.dp(HistoryActivity.this, 11));
            if (!(parent instanceof GridView)
                    && sort != null && sort.getSelectedItemPosition() == 3) {
                final int currentSource = LibraryOrganizer.sourceOf(task);
                final boolean firstInGroup = position == 0
                        || LibraryOrganizer.sourceOf(items.get(position - 1)) != currentSource;
                if (firstInGroup) {
                    final TextView groupTitle = Ui.eyebrow(HistoryActivity.this,
                            sourceLabel(currentSource));
                    groupTitle.setPadding(0, Ui.dp(HistoryActivity.this, 10),
                            0, Ui.dp(HistoryActivity.this, 12));
                    wrap.addView(groupTitle, new LinearLayout.LayoutParams(-1, -2));
                }
            }
            wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
            return wrap;
        }
    }
}
