package android.content;

public abstract class Context {
    public static final String NOTIFICATION_SERVICE = "notification";
    public static final int CONTEXT_IGNORE_SECURITY = 2;

    public abstract Object getSystemService(String name);

    public abstract String getOpPackageName();

    public abstract Context createPackageContext(String packageName, int flags) throws Exception;
}
