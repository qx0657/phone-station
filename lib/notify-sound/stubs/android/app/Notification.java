package android.app;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;

public class Notification {
    public Bundle extras;

    public static class Style {}

    public static class BigTextStyle extends Style {
        public BigTextStyle bigText(CharSequence text) {
            return this;
        }
    }

    public static class Builder {
        public Builder(Context context, String channelId) {}

        public Builder setSmallIcon(Icon icon) {
            return this;
        }

        public Builder setContentTitle(CharSequence title) {
            return this;
        }

        public Builder setContentText(CharSequence text) {
            return this;
        }

        public Builder setStyle(Style style) {
            return this;
        }

        public Builder setWhen(long when) {
            return this;
        }

        public Builder setShowWhen(boolean show) {
            return this;
        }

        public Builder setOnlyAlertOnce(boolean onlyAlertOnce) {
            return this;
        }

        public Notification build() {
            return null;
        }
    }
}
