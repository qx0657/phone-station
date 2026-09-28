package android.media;

public class AudioTrack {
    public static final int MODE_STREAM = 1;
    public static final int STATE_INITIALIZED = 1;

    public AudioTrack(AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes,
            int mode, int sessionId) {}

    public static int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat) {
        return 0;
    }

    public int getState() { return 0; }
    public void play() {}
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) { return 0; }
    public int getPlaybackHeadPosition() { return 0; }
    public void stop() {}
    public void release() {}
}
