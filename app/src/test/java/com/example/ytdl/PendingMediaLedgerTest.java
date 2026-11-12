package com.example.ytdl;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.MediaStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class PendingMediaLedgerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final long NOW = 1_900_000_000_000L;
    private static final Uri VIDEO = Uri.parse("content://media/external/downloads/123");
    private static final Uri AUDIO = Uri.parse("content://media/external/audio/media/456");
    private FakeMediaProvider provider;

    public static final class FakeMediaProvider extends ContentProvider {
        final Map<String, Integer> rows = new HashMap<>();
        int deletes;

        @Override public boolean onCreate() { return true; }

        @Override public Cursor query(Uri uri, String[] columns,
                                      String selection, String[] selectionArgs,
                                      String orderBy) {
            final Integer state = rows.get(uri.buildUpon().clearQuery().build().toString());
            final MatrixCursor c = new MatrixCursor(
                    new String[]{MediaStore.MediaColumns.IS_PENDING});
            if (state != null) c.addRow(new Object[]{state});
            return c;
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            final String key = uri.buildUpon().clearQuery().build().toString();
            if (rows.remove(key) != null) {
                deletes++;
                return 1;
            }
            return 0;
        }

        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int update(Uri uri, ContentValues values, String selection,
                                    String[] selectionArgs) { return 0; }
        @Override public String getType(Uri uri) { return null; }
    }

    @Before public void setup() {
        provider = new FakeMediaProvider();
        ShadowContentResolver.registerProviderInternal("media", provider);
    }

    private PendingMediaLedger ledger() {
        return new PendingMediaLedger(new File(folder.getRoot(), "ledger.json"));
    }

    @Test public void acceptsOnlyItemUrisInExpectedMediaCollections() {
        assertTrue(PendingMediaLedger.isOwnedItemUri(VIDEO));
        assertTrue(PendingMediaLedger.isOwnedItemUri(AUDIO));
        for (String uri : new String[]{"content://media/external/file/1",
                "content://other/external/downloads/2", "file:///downloads/3",
                "content://media/external/downloads/0",
                "content://media/external/downloads/4/extra",
                "content://media/external/downloads/-1"}) {
            assertFalse(uri, PendingMediaLedger.isOwnedItemUri(Uri.parse(uri)));
        }
    }

    @Test public void recordsArePersistedAndCanBeForgotten() throws Exception {
        final File path = new File(folder.getRoot(), "ledger.json");
        final PendingMediaLedger original = new PendingMediaLedger(path);
        original.record(VIDEO, NOW - PendingMediaLedger.MIN_CLEANUP_AGE_MS - 5);
        original.record(VIDEO, NOW - PendingMediaLedger.MIN_CLEANUP_AGE_MS - 5);
        original.record(AUDIO, NOW - PendingMediaLedger.MIN_CLEANUP_AGE_MS - 5);
        assertEquals(2, new PendingMediaLedger(path).snapshot().size());
        original.forget(VIDEO);
        assertEquals(1, new PendingMediaLedger(path).snapshot().size());
        assertEquals(AUDIO.toString(), original.snapshot().get(0).uri);
    }

    @Test public void removesOnlyConfirmedOldPendingRows() throws Exception {
        final PendingMediaLedger ledger = ledger();
        ledger.record(VIDEO, NOW - PendingMediaLedger.MIN_CLEANUP_AGE_MS - 1000);
        ledger.record(AUDIO, NOW - PendingMediaLedger.MIN_CLEANUP_AGE_MS + 1000);
        provider.rows.put(VIDEO.toString(), 1);
        provider.rows.put(AUDIO.toString(), 1);
        assertEquals(1, ledger.inspect(RuntimeEnvironment.getApplication()
                .getContentResolver(), NOW).eligible);
        final PendingMediaLedger.Cleanup result = ledger.cleanup(
                RuntimeEnvironment.getApplication().getContentResolver(),
                NOW, false, new ReentrantLock());
        assertEquals(1, result.rowsDeleted);
        assertEquals(0, result.failures);
        assertFalse(provider.rows.containsKey(VIDEO.toString()));
        assertTrue(provider.rows.containsKey(AUDIO.toString()));
        assertEquals(1, ledger.snapshot().size());
    }

    @Test public void finishedFilesAreNeverDeletedEvenIfLedgerStillListsThem()
            throws Exception {
        final PendingMediaLedger ledger = ledger();
        ledger.record(VIDEO, NOW - PendingMediaLedger.MIN_CLEANUP_AGE_MS - 1000);
        provider.rows.put(VIDEO.toString(), 0);
        final PendingMediaLedger.Cleanup result = ledger.cleanup(
                RuntimeEnvironment.getApplication().getContentResolver(),
                NOW, false, new ReentrantLock());
        assertEquals(0, result.rowsDeleted);
        assertEquals(1, result.recordsReconciled);
        assertEquals(0, provider.deletes);
        assertTrue(ledger.snapshot().isEmpty());
        assertTrue(provider.rows.containsKey(VIDEO.toString()));
    }

    @Test public void refusesCleanupWhenWorkerIsBusy() throws Exception {
        final PendingMediaLedger ledger = ledger();
        ledger.record(VIDEO, NOW - PendingMediaLedger.MIN_CLEANUP_AGE_MS - 1000);
        provider.rows.put(VIDEO.toString(), 1);
        final PendingMediaLedger.Cleanup result = ledger.cleanup(
                RuntimeEnvironment.getApplication().getContentResolver(),
                NOW, true, new ReentrantLock());
        assertTrue(result.blockedByTransfer);
        assertEquals(0, result.rowsDeleted);
        assertTrue(provider.rows.containsKey(VIDEO.toString()));
    }

    @Test public void corruptLedgerIsNotSilentlyOverwritten() throws Exception {
        final File path = new File(folder.getRoot(), "ledger.json");
        Files.write(path.toPath(), "{unreadable".getBytes(StandardCharsets.UTF_8));
        try {
            new PendingMediaLedger(path).record(VIDEO, NOW);
            fail("Unexpectedly replaced unreadable ledger");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("unreadable"));
        }
        assertEquals("{unreadable",
                new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
    }
}
