package android.hardware.camera2;

public class CameraAccessException extends Exception {
    public static final int CAMERA_ERROR = 3;
    public static final int CAMERA_IN_USE = 4;

    public CameraAccessException(int problem) {}

    public int getReason() {
        return 0;
    }
}
