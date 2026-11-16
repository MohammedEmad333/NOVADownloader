package com.example.ytdl;

/** Context-sensitive bilingual messages when Library filters return no files. */
final class LibraryEmptyState {
    final String englishTitle;
    final String arabicTitle;
    final String englishHint;
    final String arabicHint;

    private LibraryEmptyState(String englishTitle, String arabicTitle,
                              String englishHint, String arabicHint) {
        this.englishTitle = englishTitle;
        this.arabicTitle = arabicTitle;
        this.englishHint = englishHint;
        this.arabicHint = arabicHint;
    }

    static LibraryEmptyState forFilters(int finished, String query, int category, int source) {
        if (finished == 0) {
            return new LibraryEmptyState("Your library is empty", "مكتبتك فارغة",
                    "Finish a download or import a video to see it here.",
                    "أكمل تنزيلًا أو استورد فيديو ليظهر هنا.");
        }
        if (query != null && !query.trim().isEmpty()) {
            return new LibraryEmptyState("No matching files", "لا توجد ملفات مطابقة",
                    "Try another title or clear the search.",
                    "جرّب عنوانًا آخر أو امسح البحث.");
        }
        if (category == 6) {
            return new LibraryEmptyState("No possible copies found", "لا توجد نسخ محتملة",
                    "Nothing to review. No saved files were changed.",
                    "لا توجد نسخ للمراجعة. لم يتم تعديل أي ملف محفوظ.");
        }
        if (category == LibraryFilter.FAVORITES) {
            return new LibraryEmptyState("No favorites here", "لا توجد ملفات مفضلة",
                    "Mark a saved file as a favorite to find it here.",
                    "أضف ملفًا محفوظًا إلى المفضلة ليظهر هنا.");
        }
        if (category == LibraryFilter.NEEDS_ATTENTION) {
            return new LibraryEmptyState("No downloads need attention", "لا توجد تنزيلات تحتاج متابعة",
                    "Failed or cancelled downloads will appear here.",
                    "ستظهر هنا التنزيلات الفاشلة أو الملغاة.");
        }
        if (source != LibraryOrganizer.ALL_SOURCES) {
            return new LibraryEmptyState("No files from this source", "لا توجد ملفات من هذا المصدر",
                    "Choose another source or show every source.",
                    "اختر مصدرًا آخر أو اعرض جميع المصادر.");
        }
        return new LibraryEmptyState("No files in this category", "لا توجد ملفات في هذا التصنيف",
                "Select another category to see your saved files.",
                "اختر تصنيفًا آخر لعرض ملفاتك المحفوظة.");
    }
}
