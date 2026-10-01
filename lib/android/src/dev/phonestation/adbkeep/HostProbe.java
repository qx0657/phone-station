package dev.phonestation.adbkeep;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/** 读电脑写进 {@link HostLink#SETTING} 的时间，合成界面顶部的连接状态。 */
final class HostProbe {
    private static String lastHeadline;

    final String headline;
    final boolean linked;

    private HostProbe(String headline, boolean linked) {
        this.headline = headline;
        this.linked = linked;
    }

    static HostProbe current(Context context) {
        long marked = Settings.Global.getLong(
                context.getContentResolver(), HostLink.SETTING, 0L);
        boolean linked = HostLink.linked(marked, System.currentTimeMillis());
        String headline = HostLink.headline(linked);
        if (!headline.equals(lastHeadline)) {
            Log.i(KeeperEngine.TAG, "host " + headline);
            lastHeadline = headline;
        }
        return new HostProbe(headline, linked);
    }
}
