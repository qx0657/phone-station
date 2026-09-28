import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Arrays;

/** 给截图写 16×16 灰度指纹。只读原图，用来找候选，不能单独当删除条件。 */
public class HashShots {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: HashShots <src-dir> <out.tsv>");
            System.exit(2);
        }
        File src = new File(args[0]);
        File[] files = src.listFiles();
        if (files == null) {
            throw new IllegalStateException("cannot list " + src);
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(args[1]), "UTF-8"));
        out.println("name\tbytes\tmtime\tw\th\tgray16");
        int done = 0;
        int errors = 0;
        long t0 = System.currentTimeMillis();
        for (File file : files) {
            String name = file.getName();
            String lower = name.toLowerCase();
            if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
                continue;
            }
            if (!file.isFile()) {
                continue;
            }
            try {
                out.println(hashOne(file));
                out.flush();
                done++;
                if (done % 500 == 0) {
                    long sec = (System.currentTimeMillis() - t0) / 1000;
                    System.err.println("progress " + done + " in " + sec + "s");
                }
            } catch (Throwable t) {
                errors++;
                String msg = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
                msg = msg.replace('\t', ' ').replace('\n', ' ');
                out.println(name + "\t" + file.length() + "\t" + file.lastModified() + "\t0\t0\tERR " + msg);
                out.flush();
                System.err.println("error " + name + " " + msg);
            }
        }
        out.close();
        long sec = (System.currentTimeMillis() - t0) / 1000;
        System.err.println("finished done=" + done + " errors=" + errors + " sec=" + sec);
    }

    static String hashOne(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) {
            throw new IllegalStateException("bad bounds");
        }
        int sample = 1;
        while (w / sample > 96 && h / sample > 96) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (decoded == null) {
            throw new IllegalStateException("decode null");
        }
        Bitmap grid = Bitmap.createScaledBitmap(decoded, 16, 16, true);
        decoded.recycle();
        String gray16 = gray16(grid);
        grid.recycle();
        return file.getName() + "\t" + file.length() + "\t" + file.lastModified()
                + "\t" + w + "\t" + h + "\t" + gray16;
    }

    static int gray(int pixel) {
        int r = (pixel >> 16) & 0xff;
        int g = (pixel >> 8) & 0xff;
        int b = pixel & 0xff;
        return (r * 30 + g * 59 + b * 11) / 100;
    }

    static String gray16(Bitmap bmp) {
        int[] px = new int[256];
        bmp.getPixels(px, 0, 16, 0, 0, 16, 16);
        char[] hex = new char[512];
        for (int i = 0; i < 256; i++) {
            int v = gray(px[i]);
            hex[i * 2] = Character.forDigit(v >>> 4, 16);
            hex[i * 2 + 1] = Character.forDigit(v & 0xf, 16);
        }
        return new String(hex);
    }
}
