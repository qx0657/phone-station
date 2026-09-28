package android.graphics;

public class Bitmap {
    public enum Config {
        RGB_565, ARGB_8888
    }

    public static Bitmap createScaledBitmap(Bitmap src, int dstWidth, int dstHeight, boolean filter) {
        return null;
    }

    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {}
    public void recycle() {}
}
