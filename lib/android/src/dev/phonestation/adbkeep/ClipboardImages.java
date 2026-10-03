package dev.phonestation.adbkeep;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/** Publish a complete PNG to the system album without replacing the phone clipboard. */
final class ClipboardImages {
    static void save(Context context, byte[] png) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || (long)bounds.outWidth * bounds.outHeight > 32_000_000L
                || !"image/png".equals(bounds.outMimeType)) {
            throw new FileFailure("图片无效或超过 3200 万像素，已跳过");
        }
        BitmapFactory.Options preview = new BitmapFactory.Options();
        preview.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / preview.inSampleSize > 1024) {
            preview.inSampleSize *= 2;
        }
        android.graphics.Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length, preview);
        if (bitmap == null) { throw new FileFailure("图片无效，已跳过"); }
        bitmap.recycle();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "Mac-" + UUID.randomUUID() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/手机工位");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) { throw new IOException("insert failed"); }
            try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                if (output == null) { throw new IOException("open failed"); }
                output.write(png);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, values, null, null) != 1) {
                throw new IOException("publish failed");
            }
            // MediaStore publishes and notifies album observers; no obsolete file-URI broadcast needed.
            context.getContentResolver().notifyChange(uri, null);
        } catch (IOException | RuntimeException error) {
            if (uri != null) {
                try { context.getContentResolver().delete(uri, null, null); } catch (RuntimeException ignored) {}
            }
            throw new FileFailure("图片未能保存到相册，请检查存储空间并重新复制");
        }
        ClipboardImageNotice.post(context, uri);
    }
}
