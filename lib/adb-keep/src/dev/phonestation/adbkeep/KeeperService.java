package dev.phonestation.adbkeep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

public final class KeeperService extends Service {
    private static final String CHANNEL = "keep";
    private static final int NOTIF_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickTask = this::onTick;
    private ContentObserver settingObserver;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private boolean callbacksRegistered;
    private boolean probed;

    static void start(Context context) {
        Intent intent = new Intent(context, KeeperService.class);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException error) {
            android.util.Log.w(KeeperEngine.TAG, "start service failed", error);
            long next = KeeperEngine.tick(context);
            if (next >= 0L) {
                KeeperAlarm.schedule(context, next);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "无线调试保持", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        if (!KeeperStore.isEnabled(this)) {
            shutdown();
            return START_NOT_STICKY;
        }
        registerCallbacks();
        if (!probed) {
            probed = true;
            KeeperEngine.probeWrite(this);
        }
        onTick();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterCallbacks();
        handler.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void onTick() {
        if (!KeeperStore.isEnabled(this)) {
            shutdown();
            return;
        }
        long next = KeeperEngine.tick(this);
        if (next < 0L) {
            shutdown();
            return;
        }
        KeeperAlarm.schedule(this, next);
        handler.removeCallbacks(tickTask);
        handler.postDelayed(tickTask, next);
        startInForeground();
    }

    private void shutdown() {
        KeeperAlarm.cancel(this);
        unregisterCallbacks();
        handler.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void registerCallbacks() {
        if (callbacksRegistered) {
            return;
        }
        callbacksRegistered = true;
        settingObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                handler.removeCallbacks(tickTask);
                handler.post(tickTask);
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"), false, settingObserver);

        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            return;
        }
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(tickTask);
            }

            @Override
            public void onLost(Network network) {
                handler.post(tickTask);
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        connectivity.registerNetworkCallback(request, wifiCallback);
    }

    private void unregisterCallbacks() {
        if (!callbacksRegistered) {
            return;
        }
        callbacksRegistered = false;
        if (settingObserver != null) {
            getContentResolver().unregisterContentObserver(settingObserver);
            settingObserver = null;
        }
        if (wifiCallback != null) {
            ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
            if (connectivity != null) {
                connectivity.unregisterNetworkCallback(wifiCallback);
            }
            wifiCallback = null;
        }
    }

    private void startInForeground() {
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("无线调试保持")
                .setContentText(KeeperEngine.summary(this))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private PendingIntent openApp() {
        Intent intent = new Intent(this, MainActivity.class);
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
