package dev.phonestation.adbkeep;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/** 合并内存里的本地心跳和远程通道，供首页与常驻通知使用。 */
final class HostProbe {
    private static String lastHeadline;

    final String headline;
    final boolean linked;
    final boolean remoteConnected;
    final String connectionType;
    final boolean remoteChecking;
    final boolean localConnected;
    final String localTransport;

    private HostProbe(String headline, boolean linked, boolean remoteConnected, String connectionType, boolean remoteChecking, boolean localConnected, String localTransport) {
        this.headline = headline;
        this.linked = linked;
        this.remoteConnected = remoteConnected;
        this.connectionType = connectionType;
        this.remoteChecking = remoteChecking;
        this.localConnected = localConnected;
        this.localTransport = localTransport == null ? "" : localTransport;
    }

    static HostProbe current(Context context) {
        boolean remoteConnected = RemoteStatus.connected(context);
        boolean remoteChecking = RemoteStatus.checking(context);
        boolean local = HostPresence.fresh()
                && Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        boolean adbWifi = Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        String transport = HostLink.localTransport(local, HostPresence.transport(), adbWifi);
        long now = System.currentTimeMillis();
        long marked = local ? now : 0L;
        boolean linked = HostLink.linked(marked, now, remoteConnected);
        String connectionType = HostLink.connectionType(marked, now, transport, remoteConnected);
        String headline = !linked && remoteChecking ? "确认连接中" : HostLink.headline(linked);
        if (!linked && remoteChecking) { connectionType = "远程连接待确认"; }
        if (!headline.equals(lastHeadline)) {
            Log.i(KeeperEngine.TAG, "host " + headline);
            lastHeadline = headline;
        }
        return new HostProbe(headline, linked, remoteConnected, connectionType, remoteChecking, local, transport);
    }

    static boolean remoteOnly(Context context) {
        HostProbe host = current(context);
        return host.remoteConnected && !host.localConnected;
    }
}
