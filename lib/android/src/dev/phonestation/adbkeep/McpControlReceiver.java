package dev.phonestation.adbkeep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** 电脑经 adb 记住并开关 MCP。和会话提醒一样，只收 shell 或本应用。 */
public final class McpControlReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.phonestation.adbkeep.MCP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 34
                && !AlertSender.allowed(getSentFromUid(), android.os.Process.myUid(), intent.getFlags())) {
            Log.w(KeeperEngine.TAG, "mcp rejected");
            return;
        }
        setResultCode(0);
        boolean on = intent.getBooleanExtra("on", false);
        KeeperStore.setMcpEnabled(context, on);
        if (on) {
            FileMcpService.start(context);
        } else {
            RemoteStore.setEnabled(context, false);
            FileMcpService.stop(context);
        }
        setResultCode(1);
    }
}
