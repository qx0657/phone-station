package dev.phonestation.adbkeep;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.security.SecureRandom;

/**
 * 本机 MCP。文件是其中一项。Android 15 上 shell 拉不起没有 exported 的前台服务，所以这项是 exported。
 * 只听 127.0.0.1，并且每个请求都要带启动时打进日志的令牌。普通应用读不了这条日志。
 */
public final class FileMcpService extends Service {
    static final String TAG = "StationMcp";
    static final int PORT = 8765;
    /** 电脑读这一项判断服务是否在听。1 开着，其余都算关着。 */
    static final String LISTEN_SETTING = "phonestation_mcp";
    private static volatile boolean listening;

    private McpHttp server;
    private String token;
    private PhoneRelayClient remoteClient;

    static boolean listening() {
        return listening;
    }

    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, FileMcpService.class));
        } catch (RuntimeException error) {
            Log.w(TAG, "start service failed", error);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, FileMcpService.class));
    }

    /** 手机本机上的地址。电脑要经 adb 转发，那个地址由 mcp.sh 打印。 */
    static String listenUrl() {
        return "http://127.0.0.1:" + PORT + "/mcp";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        StationNotifications.attachMcp(this);
        StationNotifications.enter(this);
        if (server == null && KeeperStore.mcpEnabled(this)) {
            token = newToken();
            try {
                FileOps files = FileOps.device();
                files.setMediaNotice(new MediaScan(this));
                server = McpHttp.open(PORT, token, files, new StationBridge(this, files), versionName());
            } catch (IOException error) {
                Log.e(TAG, "mcp listen failed", error);
                listening = false;
                publishListening(false);
                if (!RemoteStore.enabled(this) || !RemoteStore.configured(this)) {
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }
            if (server != null) {
                listening = true;
                KeeperStore.setMcpEnabled(this, true);
                publishListening(true);
                logStorage();
            }
        }
        if (server != null) {
            Log.i(TAG, "mcp token " + token);
        }
        if (RemoteStore.enabled(this) && RemoteStore.configured(this) && remoteClient == null) {
            remoteClient = new PhoneRelayClient(this, versionName());
            remoteClient.start();
        } else if (!RemoteStore.enabled(this) && remoteClient != null) {
            remoteClient.stop();
            remoteClient = null;
        }
        if (server == null && remoteClient == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return RemoteStore.enabled(this) ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        listening = false;
        publishListening(false);
        McpHttp running = server;
        server = null;
        token = null;
        if (running != null) {
            running.close();
        }
        PhoneRelayClient remote = remoteClient;
        remoteClient = null;
        if (remote != null) {
            remote.stop();
        }
        StationNotifications.detachMcp(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** ActivityManager 的 dumpsys service 入口受系统 DUMP 权限保护，供 adb 刷新本地凭据。 */
    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (server != null && token != null) {
            writer.println("mcp token " + token);
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "0";
        }
    }

    /** API 29 没有 isExternalStorageManager，直接调用会在那一档上验证失败。 */
    private static void logStorage() {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        try {
            Method method = Environment.class.getMethod("isExternalStorageManager");
            Log.i(TAG, "storage " + method.invoke(null));
        } catch (ReflectiveOperationException error) {
            Log.i(TAG, "storage unknown");
        }
    }

    private void publishListening(boolean on) {
        try {
            Settings.Global.putInt(getContentResolver(), LISTEN_SETTING, on ? 1 : 0);
        } catch (RuntimeException error) {
            Log.w(TAG, "mcp flag", error);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return FileOps.toHex(bytes);
    }
}
