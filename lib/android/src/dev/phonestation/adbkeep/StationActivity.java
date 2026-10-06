package dev.phonestation.adbkeep;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

/** Refresh a returning page after display preferences change, preserving its back stack. */
public abstract class StationActivity extends Activity {
    private String appearance;
    private StationChrome chrome;
    private Bundle chromeState;
    private Dialog dialog;
    @Override protected void attachBaseContext(Context context) { super.attachBaseContext(StationAppearance.wrap(context)); }
    @Override protected void onCreate(Bundle state) {
        appearance = StationAppearance.signature(this);
        chromeState = state;
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.Theme_PhoneStation_Dark : R.style.Theme_PhoneStation_Light);
        super.onCreate(state);
    }
    void chrome(StationChrome value) { chrome = value; value.restoreDisplayState(chromeState); }
    void trackDialog(Dialog value) {
        if (dialog != null && dialog != value) { dialog.dismiss(); }
        dialog = value;
        value.setOnDismissListener(ignored -> { if (dialog == value) { dialog = null; } });
    }
    @Override protected void onDestroy() {
        if (dialog != null) { dialog.dismiss(); }
        super.onDestroy();
    }
    @Override protected void onResume() {
        super.onResume();
        if (!StationAppearance.signature(this).equals(appearance)) { recreate(); }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (chrome != null) { chrome.saveDisplayState(state); }
        super.onSaveInstanceState(state);
    }
}
