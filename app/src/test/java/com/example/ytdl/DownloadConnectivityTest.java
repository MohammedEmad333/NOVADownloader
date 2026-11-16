package com.example.ytdl;

import android.net.NetworkCapabilities;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class DownloadConnectivityTest {
    @Test public void noConnectedNetworkBlocksWorkInsteadOfBusyRetrying() {
        assertFalse(DownloadConnectivity.canTransfer(false, false, false));
        assertFalse(DownloadConnectivity.canTransfer(false, true, true));
        assertFalse(DownloadConnectivity.canTransfer(true, true, false));
        assertTrue(DownloadConnectivity.canTransfer(true, false, false));
        assertTrue(DownloadConnectivity.canTransfer(true, true, true));
    }

    @Test public void capabilityCheckDoesNotMistakeAnyNetworkForInternet() {
        final NetworkCapabilities missing = new NetworkCapabilities.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        final NetworkCapabilities allowed = new NetworkCapabilities.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        assertFalse(DownloadConnectivity.hasInternet(null));
        assertFalse(DownloadConnectivity.hasInternet(missing));
        assertTrue(DownloadConnectivity.hasInternet(allowed));
    }

    @Test public void connectionReminderIsRateLimitedAndRequiresQueuedWork() {
        final long now = 1_900_000_000_000L;
        assertTrue(NetworkRecoveryWorker.shouldNotify(now, 0, true, true, false));
        assertFalse(NetworkRecoveryWorker.shouldNotify(now, now - 5 * 60_000L,
                true, true, false));
        assertFalse(NetworkRecoveryWorker.shouldNotify(now, 0,
                false, true, false));
        assertFalse(NetworkRecoveryWorker.shouldNotify(now, 0,
                true, false, false));
        assertFalse(NetworkRecoveryWorker.shouldNotify(now, 0,
                true, true, true));
        assertTrue(NetworkRecoveryWorker.shouldNotify(now, now - 40 * 60_000L,
                true, true, false));
    }
}
