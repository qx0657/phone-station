package android.hardware.camera2;

import android.os.Handler;

public class CameraManager {
    public static abstract class TorchCallback {
        public void onTorchModeUnavailable(String cameraId) {}

        public void onTorchModeChanged(String cameraId, boolean enabled) {}

        public void onTorchStrengthLevelChanged(String cameraId, int newStrengthLevel) {}
    }

    public String[] getCameraIdList() throws CameraAccessException {
        return new String[0];
    }

    public CameraCharacteristics getCameraCharacteristics(String cameraId) throws CameraAccessException {
        return null;
    }

    public void setTorchMode(String cameraId, boolean enabled) throws CameraAccessException {}

    public void turnOnTorchWithStrengthLevel(String cameraId, int torchStrength) throws CameraAccessException {}

    public int getTorchStrengthLevel(String cameraId) throws CameraAccessException {
        return 0;
    }

    public void registerTorchCallback(TorchCallback callback, Handler handler) {}
}
