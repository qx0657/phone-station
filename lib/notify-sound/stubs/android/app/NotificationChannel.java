package android.app;

import android.media.AudioAttributes;
import android.net.Uri;

public class NotificationChannel {
    public NotificationChannel(String id, CharSequence name, int importance) {}

    public void setSound(Uri sound, AudioAttributes audioAttributes) {}

    public void enableVibration(boolean vibration) {}

    public void setShowBadge(boolean showBadge) {}
}
