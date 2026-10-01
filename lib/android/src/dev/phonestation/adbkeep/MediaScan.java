package dev.phonestation.adbkeep;

import android.content.Context;
import android.media.MediaScannerConnection;

import java.util.List;

/** 把变更过的路径交给系统媒体库。扫描是异步的，这一步只负责提交。 */
final class MediaScan implements FileOps.MediaNotice {
    private final Context context;

    MediaScan(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void changed(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        String[] list = new String[paths.size()];
        for (int i = 0; i < paths.size(); i++) {
            list[i] = paths.get(i);
        }
        MediaScannerConnection.scanFile(context, list, null, null);
    }
}
