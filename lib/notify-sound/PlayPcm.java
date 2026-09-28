import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.FileInputStream;

public class PlayPcm {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("usage: PlayPcm <pcm> <sampleRate>");
            System.exit(2);
        }
        String path = args[0];
        int rate = Integer.parseInt(args[1]);
        FileInputStream in = new FileInputStream(path);
        byte[] data = in.readAllBytes();
        in.close();
        if (data.length < 2) {
            System.out.println("空的音频");
            System.exit(1);
        }

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(min, 8192);
        AudioTrack track = new AudioTrack(attrs, format, buf, AudioTrack.MODE_STREAM, 0);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            System.out.println("AudioTrack 初始化失败");
            System.exit(1);
        }
        track.play();
        int off = 0;
        while (off < data.length) {
            int n = Math.min(buf, data.length - off);
            int wrote = track.write(data, off, n);
            if (wrote < 0) {
                System.out.println("写入失败 " + wrote);
                System.exit(1);
            }
            off += wrote;
        }
        int frames = data.length / 2;
        long deadline = System.currentTimeMillis() + (frames * 1000L / rate) + 2000L;
        while (track.getPlaybackHeadPosition() < frames
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        track.stop();
        track.release();
        System.out.println("played");
        // AudioTrack 留下非 daemon 线程。直接返回的话 VM 不退出，adb shell 会一直等。
        System.exit(0);
    }
}
