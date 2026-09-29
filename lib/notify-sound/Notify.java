import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Looper;

import java.lang.reflect.Field;

// 下拉栏里的通知。通道不发声、不震动，铃声仍由 PlayPcm 走媒体音量。
// replace：tag phone-station、id 1，后一次调用改掉前一次的标题和内容。
// stack：每次换一个 tag，id 固定为 2，和上面那条、以及更早的 stack 互不覆盖。
public class Notify {
    private static final String CHANNEL_ID = "phone-station";
    private static final String TAG = "phone-station";
    private static final int NOTIFICATION_ID = 1;
    // 和 id 1 错开。stack 靠 tag 区分，每次都不一样。
    private static final int STACK_ID = 2;
    // cmd notification post 用的固定 id。同一个 tag 下它和上面不是同一条。
    private static final int SHELL_COMMAND_ID = 2020;
    // android.R 里 stat_notify_chat 的资源号，和 cmd notification 的默认图标一样。
    private static final int ICON = 0x01080077;

    public static void main(String[] args) {
        if (args.length != 3 || args[0].isEmpty() || args[1].isEmpty()
                || (!"replace".equals(args[2]) && !"stack".equals(args[2]))) {
            System.err.println("usage: Notify <title> <text> <replace|stack>");
            System.exit(2);
        }
        boolean stack = "stack".equals(args[2]);
        try {
            exemptHiddenApi();
            Looper.prepareMainLooper();
            Context context = shellContext();
            Object service = context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (!(service instanceof NotificationManager)) {
                fail("拿不到通知服务");
            }
            NotificationManager manager = (NotificationManager) service;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "手机工位", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);

            String text = args[1];
            Notification notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(Icon.createWithResource("android", ICON))
                    .setContentTitle(args[0])
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setOnlyAlertOnce(true)
                    .build();
            if (notification.extras != null) {
                notification.extras.putString("android.substName", "手机工位");
            }
            manager.cancel(TAG, SHELL_COMMAND_ID);
            if (stack) {
                manager.notify(stackTag(), STACK_ID, notification);
                System.err.println("通知已发出");
            } else {
                manager.notify(TAG, NOTIFICATION_ID, notification);
                System.err.println("通知已更新");
            }
            System.exit(0);
        } catch (Throwable t) {
            fail(explain(t));
        }
    }

    private static String stackTag() {
        return "phone-station-" + System.currentTimeMillis()
                + "-" + Long.toUnsignedString(System.nanoTime());
    }

    private static Context shellContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context shell = system.createPackageContext(
                "com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        // 系统上下文造出来的包，调用包名仍是 android。通知服务会拒绝这种组合。
        setOpPackage(shell, "com.android.shell");
        return shell;
    }

    private static void setOpPackage(Context context, String packageName) throws Exception {
        Class<?> cls = context.getClass();
        boolean changed = false;
        while (cls != null) {
            try {
                Field field = cls.getDeclaredField("mOpPackageName");
                field.setAccessible(true);
                field.set(context, packageName);
                changed = true;
                break;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        cls = context.getClass();
        while (cls != null) {
            try {
                Field field = cls.getDeclaredField("mAttributionSource");
                field.setAccessible(true);
                Object source = field.get(context);
                if (source != null) {
                    Object updated = source.getClass()
                            .getMethod("withPackageName", String.class)
                            .invoke(source, packageName);
                    field.set(context, updated);
                }
                changed = true;
                break;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        if (!changed) {
            throw new IllegalStateException("改不了通知的调用包名");
        }
        String op = context.getOpPackageName();
        if (!packageName.equals(op)) {
            throw new IllegalStateException("调用包名是 " + op);
        }
    }

    private static void exemptHiddenApi() {
        try {
            Class<?> runtimeClass = Class.forName("dalvik.system.VMRuntime");
            Object runtime = runtimeClass.getMethod("getRuntime").invoke(null);
            runtimeClass.getMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, new Object[] {new String[] {"L"}});
        } catch (Throwable ignored) {
        }
    }

    private static String explain(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return message;
    }

    private static void fail(String message) {
        System.err.println("通知没有发出。" + message);
        System.exit(1);
    }
}
