package dev.phonestation.adbkeep;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

public final class KeeperService extends Service {
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
    public int onStartCommand(Intent intent, int flags, int startId) {
        StationNotifications.attachKeeper(this);
        StationNotifications.enter(this);
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
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        StationNotifications.update(this);
    }

    @Override
    public void onDestroy() {
        unregisterCallbacks();
        handler.removeCallbacksAndMessages(null);
        StationNotifications.detachKeeper(this);
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
        StationNotifications.update(this);
    }

    private void shutdown() {
        KeeperAlarm.cancel(this);
        unregisterCallbacks();
        handler.removeCallbacksAndMessages(null);
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
}
