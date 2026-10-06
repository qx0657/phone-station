package dev.phonestation.adbkeep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.io.IOException;
import java.util.function.Consumer;

/** Subscription renewals extend only an already established, explicitly enabled sharing session. */
final class RemoteEvents implements AutoCloseable {
    private final Context context;
    private final RelaySocket socket;
    private final Consumer<String> authorize;
    private final java.util.function.BooleanSupplier current;
    private volatile String id = "";
    private volatile boolean clipboard, notifications;
    private final BroadcastReceiver locks = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { changed("clipboard"); }
    };
    RemoteEvents(Context context, RelaySocket socket, Consumer<String> authorize, java.util.function.BooleanSupplier current) {
        this.context = context; this.socket = socket; this.authorize = authorize; this.current = current;
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(locks, filter, Context.RECEIVER_NOT_EXPORTED);
    }
    void update(Json subscription) throws IOException {
        id = subscription.get("subscriptionId").string();
        String clip = subscription.get("clipboard").string();
        String notes = subscription.get("notifications").string();
        clipboard = false; notifications = false;
        try {
            if (!clip.isEmpty()) { authorize.accept("station_clipboard_exchange"); clipboard = SharedClipboard.lease(context, clip); }
        } catch (RuntimeException denied) { }
        if (!clipboard) { SharedClipboard.lease(context, ""); }
        try {
            if (!notes.isEmpty()) { authorize.accept("station_notification_poll"); notifications = PhoneNotifications.lease(context, notes); }
        } catch (RuntimeException denied) { }
        if (!notifications) { PhoneNotifications.lease(context, ""); }
        socket.subscribed(id, clipboard, notifications);
    }
    void changed(String topic) {
        if (("clipboard".equals(topic) && clipboard) || ("notifications".equals(topic) && notifications)) {
            try { authorize.accept("clipboard".equals(topic) ? "station_clipboard_exchange" : "station_notification_poll"); }
            catch (RuntimeException denied) { return; }
            socket.event(id, topic);
        }
    }
    @Override public void close() {
        clipboard = false; notifications = false; id = "";
        // A retired client must not clear the replacement client's sharing leases.
        if (current.getAsBoolean()) { SharedClipboard.lease(context, ""); PhoneNotifications.lease(context, ""); }
        context.unregisterReceiver(locks);
    }
}
