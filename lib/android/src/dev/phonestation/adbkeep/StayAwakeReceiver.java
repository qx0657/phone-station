package dev.phonestation.adbkeep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** adb 与 MCP 共用手机里的原设置快照；不启动 MCP 服务。 */
public final class StayAwakeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        setResultCode(0);
        if (intent == null || !"dev.phonestation.adbkeep.STAY_AWAKE".equals(intent.getAction())) { return; }
        if (Build.VERSION.SDK_INT >= 34
                && !AlertSender.allowed(getSentFromUid(), android.os.Process.myUid(), intent.getFlags())) { return; }
        if (!intent.hasExtra("on")) { setResultData("缺少 on 参数"); return; }
        try {
            if (intent.getBooleanExtra("on", false)) { StationFeatures.require(context, "station_stay_awake"); }
            StayAwakeController.set(context, intent.getBooleanExtra("on", false));
            setResultCode(1);
        } catch (RuntimeException error) { setResultData(error.getMessage()); }
    }
}
