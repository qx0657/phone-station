package android.hardware.camera2;

public final class CameraCharacteristics {
    public static final class Key<T> {}

    public static final Key<Boolean> FLASH_INFO_AVAILABLE = null;
    public static final Key<Integer> LENS_FACING = null;
    public static final Key<Integer> FLASH_INFO_STRENGTH_MAXIMUM_LEVEL = null;

    public static final int LENS_FACING_FRONT = 0;
    public static final int LENS_FACING_BACK = 1;
    public static final int LENS_FACING_EXTERNAL = 2;

    public <T> T get(Key<T> key) {
        return null;
    }
}
