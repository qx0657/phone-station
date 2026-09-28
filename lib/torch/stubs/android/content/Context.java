package android.content;

public abstract class Context {
    public static final String CAMERA_SERVICE = "camera";

    public abstract Object getSystemService(String name);
}
