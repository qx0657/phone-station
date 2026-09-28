package android.media;

public class AudioFormat {
    public static final int ENCODING_PCM_16BIT = 2;
    public static final int CHANNEL_OUT_MONO = 4;

    public static class Builder {
        public Builder setSampleRate(int sampleRate) { return this; }
        public Builder setEncoding(int encoding) { return this; }
        public Builder setChannelMask(int channelMask) { return this; }
        public AudioFormat build() { return null; }
    }
}
