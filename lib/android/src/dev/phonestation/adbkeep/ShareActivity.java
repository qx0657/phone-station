package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 系统分享。读到的字节经已连接的链路送给 Mac。不申请存储或相机权限。 */
public final class ShareActivity extends Activity {
    private static final int LIMIT = 128 * 1024;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String message = "没有送上链路。";
        try {
            Uri uri = stream(getIntent());
            byte[] body = read(uri);
            if (FileMcpService.offerToMac(SharePaths.shareLeaf(displayName(uri), uri.getLastPathSegment()), body)) {
                message = "已送到 Mac。";
            } else {
                message = "电脑还没连上，文件还没有送上链路。";
            }
        } catch (IllegalArgumentException oversize) {
            message = "这份文件超过 128 KiB，还没有送上链路。";
        } catch (Exception ignored) {
            message = "没有送上链路。";
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }

    private static Uri stream(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    private byte[] read(Uri uri) throws Exception {
        if (uri == null) {
            throw new IllegalStateException("share");
        }
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IllegalStateException("share");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > LIMIT) {
                    throw new IllegalArgumentException("share");
                }
                if (count > 0) {
                    out.write(buffer, 0, count);
                }
            }
            return out.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        if (uri == null) {
            return null;
        }
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }
}
