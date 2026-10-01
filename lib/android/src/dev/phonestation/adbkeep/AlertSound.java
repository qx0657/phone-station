package dev.phonestation.adbkeep;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 用媒体音量播放一段短铃声。不申请音频焦点，避免把正在放的声音停掉。 */
final class AlertSound {
    private AlertSound() {}

    /** 打不开时返回 {@link AlertSoundPlan#missing()}，调用方不要发通知。 */
    static AlertSoundPlan openable(Context context, AlertSoundPlan plan) {
        if (plan.kind == AlertSoundPlan.MISSING || plan.kind == AlertSoundPlan.SILENT) {
            return plan;
        }
        if (plan.kind == AlertSoundPlan.FILE) {
            return new File(plan.value).isFile() ? plan : AlertSoundPlan.missing();
        }
        Uri uri = Uri.parse(plan.value);
        try (AssetFileDescriptor descriptor = context.getContentResolver()
                .openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null) {
                return plan;
            }
        } catch (Exception ignored) {
            // 媒体库地址打不开时，改试它背后的文件。
        }
        String path = fileBehind(context, uri);
        if (path != null && new File(path).isFile()) {
            return AlertSoundPlan.file(path);
        }
        return AlertSoundPlan.missing();
    }

    /** 在自建线程上播完。返回 {@link AlertSoundPlan#PLAYED} 或 {@link AlertSoundPlan#SOUND_FAILED}。 */
    static int play(Context context, AlertSoundPlan plan) {
        HandlerThread thread = new HandlerThread("alert-sound-play");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        CountDownLatch done = new CountDownLatch(1);
        int[] code = {AlertSoundPlan.SOUND_FAILED};
        boolean[] closed = {false};
        AtomicReference<MediaPlayer> held = new AtomicReference<>();
        handler.post(() -> {
            MediaPlayer player = new MediaPlayer();
            held.set(player);
            player.setAudioAttributes(attributes());
            player.setOnCompletionListener(mp ->
                    finish(mp, code, AlertSoundPlan.PLAYED, done, closed));
            player.setOnErrorListener((mp, what, extra) -> {
                Log.w(KeeperEngine.TAG, "alert sound error " + what + " " + extra);
                finish(mp, code, AlertSoundPlan.SOUND_FAILED, done, closed);
                return true;
            });
            try {
                if (plan.kind == AlertSoundPlan.FILE) {
                    player.setDataSource(plan.value);
                } else {
                    player.setDataSource(context, Uri.parse(plan.value));
                }
                player.prepare();
                player.start();
            } catch (Exception error) {
                Log.w(KeeperEngine.TAG, "alert sound " + error);
                finish(player, code, AlertSoundPlan.SOUND_FAILED, done, closed);
            }
        });
        try {
            if (!done.await(30, TimeUnit.SECONDS)) {
                Log.w(KeeperEngine.TAG, "alert sound timeout");
                handler.post(() -> {
                    MediaPlayer player = held.get();
                    if (player != null) {
                        release(player);
                    }
                });
                return AlertSoundPlan.SOUND_FAILED;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AlertSoundPlan.SOUND_FAILED;
        } finally {
            thread.quitSafely();
        }
        return code[0];
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    private static void finish(
            MediaPlayer player, int[] code, int result, CountDownLatch done, boolean[] closed) {
        if (closed[0]) {
            return;
        }
        closed[0] = true;
        code[0] = result;
        release(player);
        done.countDown();
    }

    private static void release(MediaPlayer player) {
        try {
            player.release();
        } catch (Exception ignored) {
            // 已经释放过，或还没进入可释放状态。
        }
    }

    private static String fileBehind(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[] {"_data"}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            String path = cursor.getString(0);
            if (path == null || path.isEmpty()) {
                return null;
            }
            return path;
        } catch (Exception ignored) {
            return null;
        }
    }
}
