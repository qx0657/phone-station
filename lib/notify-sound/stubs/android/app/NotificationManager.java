package android.app;

public class NotificationManager {
    public static final int IMPORTANCE_DEFAULT = 3;

    public void createNotificationChannel(NotificationChannel channel) {}

    public void notify(String tag, int id, Notification notification) {}

    public void cancel(String tag, int id) {}
}
