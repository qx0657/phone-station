package dev.phonestation.adbkeep.link;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/** 从已经拍好的像素里解码。不打开相机。 */
public final class LinkQr {
    private LinkQr() {}

    public static String decode(int[] pixels, int width, int height) {
        if (pixels == null || width < 21 || height < 21 || pixels.length != width * height) {
            throw new IllegalArgumentException("image");
        }
        try {
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                    new RGBLuminanceSource(width, height, pixels)))).getText();
        } catch (RuntimeException | com.google.zxing.ReaderException error) {
            throw new IllegalArgumentException("image");
        }
    }
}
