package dev.phonestation.adbkeep;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/** 合并 adb 心跳和远程通道，供首页与常驻通知使用。 */
final class HostProbe {
    private static String lastHeadline;

    final String headline;
    final boolean linked;
    final boolean remoteConnected;
    final String connectionType;

    private HostProbe(String headline, boolean linked, boolean remoteConnected, String connectionType) {
        this.headline = headline;
        this.linked = linked;
        this.remoteConnected = remoteConnected;
        this.connectionType = connectionType;
    }

    static HostProbe current(Context context) {
        long marked = Settings.Global.getLong(
                context.getContentResolver(), HostLink.SETTING, 0L);
        boolean remoteConnected = RemoteStore.enabled(context) && PhoneRelayClient.connected();
        long now = System.currentTimeMillis();
        String transport = Settings.Global.getString(context.getContentResolver(), HostLink.TRANSPORT_SETTING);
        if (!HostLink.localLinked(marked, now, transport,
                KeeperEngine.wifiHandle(context) != -1L, KeeperEngine.wifiAdbEnabled(context))) {
            marked = 0L;
        }
        boolean linked = HostLink.linked(marked, now, remoteConnected);
        String connectionType = HostLink.connectionType(marked, now, transport, remoteConnected);
        String headline = HostLink.headline(linked);
        if (!headline.equals(lastHeadline)) {
            Log.i(KeeperEngine.TAG, "host " + headline);
            lastHeadline = headline;
        }
        return new HostProbe(headline, linked, remoteConnected, connectionType);
    }
}
