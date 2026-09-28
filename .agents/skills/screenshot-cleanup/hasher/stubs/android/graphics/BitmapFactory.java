package android.graphics;

public class BitmapFactory {
    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize;
        public int outWidth;
        public int outHeight;
        public Bitmap.Config inPreferredConfig;
    }

    public static Bitmap decodeFile(String pathName, Options opts) {
        return null;
    }
}
