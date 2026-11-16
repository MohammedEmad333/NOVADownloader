package com.example.ytdl;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Conservative network readiness checks; no probes, login or network requests. */
final class DownloadConnectivity {
    private DownloadConnectivity() {}

    /** INTERNET capability is a local readiness signal, not proof a site is reachable. */
    static boolean connected(Context context) {
        final ConnectivityManager manager =
                context.getSystemService(ConnectivityManager.class);
        if (manager == null) return false;
        try {
            final Network active = manager.getActiveNetwork();
            return active != null && hasInternet(manager.getNetworkCapabilities(active));
        } catch (SecurityException denied) {
            return false;
        }
    }

    static boolean hasInternet(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    static boolean canTransfer(boolean connected, boolean wifiOnly, boolean onWifi) {
        return connected && (!wifiOnly || onWifi);
    }
}
