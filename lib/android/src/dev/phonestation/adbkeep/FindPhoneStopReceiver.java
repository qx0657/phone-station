package dev.phonestation.adbkeep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Private receiver, reached only through our immutable notification PendingIntent. */
public final class FindPhoneStopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { FindPhone.close(context); }
}
