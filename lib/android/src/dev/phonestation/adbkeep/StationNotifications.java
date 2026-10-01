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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

/**
 * 无线调试保持和 MCP服务共用一条常驻通知。
 *
 * <p>点「清除」清不掉。这台荣耀仍能把它划掉，而且同一个 id 再发不会回到下拉栏，
 * 所以划掉之后换一个 id，过一小会儿自己再发出来。短时间里连续划掉太多次就先停住，
 * 打开应用，或无线调试、MCP、连接这三项里有一项变了，再显示。
 * 退避还要等多久变了，通知上看不见，也不把它叫回来。
 */
final class StationNotifications {
    static final String EXTRA_TOKEN = "token";

    private static final String CHANNEL = "keep";
    private static final long SHADE_GRACE_MS = 2_000L;
    private static final long RETURN_DELAY_MS = 500L;
    private static final long RETURN_SLOW_MS = 1_200L;
    private static final long RETURN_WINDOW_MS = 15_000L;
    private static final int RETURN_BURST = 8;

    private static KeeperService keeperInstance;
    private static FileMcpService mcpInstance;
    private static boolean keeperFg;
    private static boolean mcpFg;
    private static int id;
    private static int token;
    private static boolean hidden;
    private static boolean forceRotate;
    private static boolean retiredLegacy;
    private static String visibleWakeKey = "";
    private static String visibleFullKey = "";
    private static String hiddenWakeKey = "";
    private static long postedAtElapsed;
    private static boolean seenInShade;
    private static ContentObserver settingObserver;
    private static Handler mainHandler;
    private static int returnGeneration;
    private static int returnBurst;
    private static long returnWindowStart;

    private StationNotifications() {}

    static void attachKeeper(KeeperService service) {
        keeperInstance = service;
        watch(service);
    }

    static void attachMcp(FileMcpService service) {
        mcpInstance = service;
        watch(service);
    }

    /** 服务刚被拉起，必须进前台。已经在前台时只按状态决定要不要改通知。 */
    static void enter(Service service) {
        boolean first = !isFg(service);
        StationNote note = current(service);
        if (!first && !forceRotate) {
            update(service);
            return;
        }
        boolean rotate = forceRotate || (hidden && (first || !note.wakeKey().equals(hiddenWakeKey)));
        forceRotate = false;
        if (rotate) {
            hidden = false;
        }
        publish(service, note, rotate);
    }

    /** 开关或连接变了。划掉期间，三项没变就不发。 */
    static void update(Service service) {
        StationNote note = current(service);
        if (id != 0 && gracePassed() && !shadeHas(service) && seenInShade) {
            seenInShade = false;
            markHidden();
        }
        if (hidden) {
            if (note.wakeKey().equals(hiddenWakeKey)) {
                return;
            }
            hidden = false;
            publish(service, note, true);
            return;
        }
        if (note.fullKey().equals(visibleFullKey)) {
            return;
        }
        publish(service, note, false);
    }

    /** 打开应用。通知还在下拉栏里就不动；被划掉了就换 id 再发。 */
    static void restore(Context context) {
        Service service = live();
        if (service == null) {
            if (hidden) {
                forceRotate = true;
                hidden = false;
            }
            return;
        }
        if (id != 0 && shadeHas(context)) {
            if (hidden) {
                hidden = false;
                update(service);
            }
            return;
        }
        if (id == 0 && !hidden) {
            return;
        }
        if (!hidden && !gracePassed()) {
            return;
        }
        hidden = false;
        publish(service, current(service), true);
    }

    static void detachKeeper(KeeperService service) {
        if (keeperInstance == service) {
            keeperInstance = null;
        }
        clearFg(service);
        handOff(service);
    }

    static void detachMcp(FileMcpService service) {
        if (mcpInstance == service) {
            mcpInstance = null;
        }
        clearFg(service);
        handOff(service);
    }

    static void onUserDismissed(int dismissed) {
        if (dismissed != token || token == 0) {
            return;
        }
        Log.i(KeeperEngine.TAG, "note dismissed");
        long now = SystemClock.elapsedRealtime();
        if (returnWindowStart == 0L || now - returnWindowStart > RETURN_WINDOW_MS) {
            returnWindowStart = now;
            returnBurst = 0;
        }
        returnBurst++;
        Service service = live();
        if (service == null || returnBurst > RETURN_BURST) {
            if (returnBurst > RETURN_BURST) {
                Log.i(KeeperEngine.TAG, "note return paused");
            }
            markHidden();
            return;
        }
        long sincePost = postedAtElapsed == 0L
                ? Long.MAX_VALUE
                : now - postedAtElapsed;
        markHidden();
        int generation = ++returnGeneration;
        handler().postDelayed(() -> {
            if (generation != returnGeneration) {
                return;
            }
            Service current = live();
            if (current == null) {
                markHidden();
                return;
            }
            hidden = false;
            publish(current, current(current), true);
        }, sincePost < RETURN_DELAY_MS ? RETURN_SLOW_MS : RETURN_DELAY_MS);
    }

    private static void markHidden() {
        if (hidden) {
            return;
        }
        hidden = true;
        hiddenWakeKey = visibleWakeKey;
        Log.i(KeeperEngine.TAG, "note hidden");
    }

    private static void handOff(Service stopping) {
        Service other = live();
        if (other == null) {
            remove(stopping);
            return;
        }
        stopping.stopForeground(Service.STOP_FOREGROUND_DETACH);
        StationNote note = current(other);
        if (hidden && note.wakeKey().equals(hiddenWakeKey)) {
            if (!isFg(other)) {
                reassert(other, note);
            }
            return;
        }
        if (hidden) {
            hidden = false;
            publish(other, note, true);
            return;
        }
        publish(other, note, false);
    }

    /** 剩下的服务还得占着原来的通知，但不把用户划掉的那条再叫出来。 */
    private static void reassert(Service service, StationNote note) {
        if (token == 0) {
            publish(service, note, false);
            return;
        }
        startFg(service, build(service, note, token));
        if (service instanceof KeeperService) {
            keeperFg = true;
        } else if (service instanceof FileMcpService) {
            mcpFg = true;
        }
    }

    private static void remove(Service service) {
        token++;
        returnGeneration++;
        hidden = false;
        forceRotate = false;
        seenInShade = false;
        postedAtElapsed = 0L;
        hiddenWakeKey = "";
        visibleWakeKey = "";
        visibleFullKey = "";
        id = 0;
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        unwatchIfIdle(service);
    }

    private static void publish(Service caller, StationNote note, boolean rotate) {
        returnGeneration++;
        Context context = caller.getApplicationContext();
        ensureChannel(context);
        int previous = id;
        if (id == 0) {
            id = StationNote.FIRST_ID;
        } else if (rotate) {
            id = StationNote.nextId(id);
        }
        int posted = ++token;
        Notification notification = build(context, note, posted);
        if (keeperInstance != null) {
            startFg(keeperInstance, notification);
            keeperFg = true;
        }
        if (mcpInstance != null) {
            startFg(mcpInstance, notification);
            mcpFg = true;
        }
        if (keeperInstance == null && mcpInstance == null) {
            startFg(caller, notification);
            markCallerFg(caller);
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null && previous != 0 && previous != id) {
            manager.cancel(previous);
        }
        retireLegacy(manager);
        visibleWakeKey = note.wakeKey();
        visibleFullKey = note.fullKey();
        seenInShade = false;
        postedAtElapsed = SystemClock.elapsedRealtime();
        Log.i(KeeperEngine.TAG, "note " + (rotate ? "again " : "") + note.title + " / " + note.text);
    }

    private static void retireLegacy(NotificationManager manager) {
        if (retiredLegacy || manager == null) {
            return;
        }
        retiredLegacy = true;
        if (id != StationNote.LEGACY_MCP_ID) {
            manager.cancel(StationNote.LEGACY_MCP_ID);
        }
        manager.deleteNotificationChannel("mcp");
    }

    private static Notification build(Context context, StationNote note, int postedToken) {
        RemoteViews collapsed = new RemoteViews(context.getPackageName(), R.layout.note_collapsed);
        RemoteViews expanded = new RemoteViews(context.getPackageName(), R.layout.note_expanded);
        bindCollapsed(context, collapsed, note);
        bindExpanded(context, expanded, note);
        return new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_station)
                .setContentTitle(note.title)
                .setContentText(note.text)
                .setCustomContentView(collapsed)
                .setCustomBigContentView(expanded)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(openApp(context))
                .setDeleteIntent(hideIntent(context, postedToken))
                .build();
    }

    private static void bindCollapsed(Context context, RemoteViews views, StationNote note) {
        paint(context, views, note);
    }

    private static void bindExpanded(Context context, RemoteViews views, StationNote note) {
        views.setTextViewText(R.id.title, note.title);
        views.setTextColor(R.id.title, toneColor(context, note));
        int ink = context.getColor(R.color.note_text);
        int quiet = context.getColor(R.color.note_muted);
        int held = context.getColor(R.color.held);
        views.setTextColor(R.id.wireless_label, ink);
        views.setTextColor(R.id.mcp_label, ink);
        views.setTextViewText(R.id.wireless_state, note.wirelessState);
        views.setTextColor(R.id.wireless_state, "开".equals(note.wirelessState) ? held : quiet);
        views.setTextViewText(R.id.mcp_state, note.mcpState);
        views.setTextColor(R.id.mcp_state, "开".equals(note.mcpState) ? held : quiet);
        if (note.expandedDetail.isEmpty()) {
            views.setViewVisibility(R.id.detail, View.GONE);
            return;
        }
        views.setViewVisibility(R.id.detail, View.VISIBLE);
        views.setTextViewText(R.id.detail, note.expandedDetail);
        views.setTextColor(R.id.detail, context.getColor(R.color.muted));
    }

    private static void paint(Context context, RemoteViews views, StationNote note) {
        views.setTextViewText(R.id.title, note.title);
        views.setTextColor(R.id.title, toneColor(context, note));
        if (note.text.isEmpty()) {
            views.setViewVisibility(R.id.detail, View.GONE);
            return;
        }
        views.setViewVisibility(R.id.detail, View.VISIBLE);
        views.setTextViewText(R.id.detail, note.text);
        views.setTextColor(R.id.detail, context.getColor(R.color.muted));
    }

    private static int toneColor(Context context, StationNote note) {
        switch (note.tone) {
            case HELD:
                return context.getColor(R.color.held);
            case WAITING:
                return context.getColor(R.color.waiting);
            case NEUTRAL:
            default:
                return context.getColor(R.color.note_text);
        }
    }

    private static void startFg(Service service, Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                service.startForeground(
                        id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                service.startForeground(id, notification);
            }
        } catch (RuntimeException error) {
            if (!foregroundStartBlocked(error)) {
                throw error;
            }
            Log.w(KeeperEngine.TAG, "note foreground", error);
            NotificationManager manager = service.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(id, notification);
            }
        }
    }

    private static boolean foregroundStartBlocked(RuntimeException error) {
        return Build.VERSION.SDK_INT >= 31
                && "android.app.ForegroundServiceStartNotAllowedException"
                        .equals(error.getClass().getName());
    }

    private static Handler handler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent hideIntent(Context context, int postedToken) {
        Intent intent = new Intent(context, StationHideReceiver.class);
        intent.setAction(StationHideReceiver.ACTION);
        intent.putExtra(EXTRA_TOKEN, postedToken);
        return PendingIntent.getBroadcast(
                context,
                postedToken,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "手机工位", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private static StationNote current(Context context) {
        HostProbe host = HostProbe.current(context);
        KeeperCopy keeper = KeeperEngine.current(context);
        boolean mcp = FileMcpService.listening() || mcpInstance != null;
        return StationNote.present(
                host.linked,
                "开".equals(keeper.wireless),
                mcp,
                keeper.headline,
                keeper.tone);
    }

    private static void watch(Context context) {
        if (settingObserver != null) {
            return;
        }
        settingObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                Service service = live();
                if (service != null) {
                    update(service);
                }
            }
        };
        context.getApplicationContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"), false, settingObserver);
        context.getApplicationContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(HostLink.SETTING), false, settingObserver);
    }

    private static void unwatchIfIdle(Context context) {
        if (keeperInstance != null || mcpInstance != null || settingObserver == null) {
            return;
        }
        context.getApplicationContext().getContentResolver()
                .unregisterContentObserver(settingObserver);
        settingObserver = null;
    }

    private static Service live() {
        return keeperInstance != null ? keeperInstance : mcpInstance;
    }

    private static boolean isFg(Service service) {
        return service instanceof KeeperService ? keeperFg : mcpFg;
    }

    private static void clearFg(Service service) {
        if (service instanceof KeeperService) {
            keeperFg = false;
        } else {
            mcpFg = false;
        }
    }

    private static void markCallerFg(Service service) {
        if (service instanceof KeeperService) {
            keeperFg = true;
        } else if (service instanceof FileMcpService) {
            mcpFg = true;
        }
    }

    private static boolean gracePassed() {
        return postedAtElapsed == 0L
                || SystemClock.elapsedRealtime() - postedAtElapsed >= SHADE_GRACE_MS;
    }

    private static boolean shadeHas(Context context) {
        if (id == 0) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return true;
        }
        try {
            StatusBarNotification[] items = manager.getActiveNotifications();
            if (items == null) {
                return false;
            }
            for (StatusBarNotification item : items) {
                if (item.getId() == id) {
                    seenInShade = true;
                    return true;
                }
            }
            return false;
        } catch (RuntimeException error) {
            Log.w(KeeperEngine.TAG, "note shade", error);
            return true;
        }
    }
}
