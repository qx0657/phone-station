package dev.phonestation.adbkeep;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.Set;

/** 只导出同步白名单里的应用图标；不读取通知或联系人图片。 */
final class NotificationAppIcon {
    static Json read(Context context, String requested) {
        Set<String> selected = PhoneNotifications.selected(context);
        String pkg = requested;
        if (pkg == null || pkg.isEmpty()) { pkg = selected.stream().sorted().findFirst().orElse(""); }
        Json result = Json.obj().put("available", false);
        if (!PhoneNotifications.state(context).allowsIcon(pkg)) { return result; }
        Bitmap bitmap = null;
        try {
            PackageManager manager = context.getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(pkg, 0);
            Drawable icon = manager.getApplicationIcon(info);
            bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Rect original = new Rect(icon.getBounds());
            try { icon.setBounds(0, 0, 96, 96); icon.draw(new Canvas(bitmap)); }
            finally { icon.setBounds(original); }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            if (bytes.size() > 65_536 || !PhoneNotifications.state(context).allowsIcon(pkg)) { return result; }
            return result.put("available", true).put("packageName", pkg)
                    .put("app", manager.getApplicationLabel(info).toString())
                    .put("mimeType", "image/png").put("base64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return result;
        } finally { if (bitmap != null) { bitmap.recycle(); } }
    }
}
