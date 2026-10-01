package dev.phonestation.adbkeep;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

/** 能读到的权限，以及还没开时怎么去开。 */
public final class PermissionActivity extends Activity {
    private static final int REQUEST_NOTIFY = 2;
    private static final int REQUEST_SHIZUKU = 3;
    private static final String[][] STARTUP = {
            {
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"
            },
            {
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            }
    };

    private StationChrome ui;
    private LinearLayout rows;
    private final Shizuku.OnBinderReceivedListener shizukuBinder = this::render;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermission =
            (code, grantResult) -> render();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new StationChrome(this);
        ui.back("权限");
        LinearLayout card = ui.card();
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        card.addView(rows, matchWrap());
        Shizuku.addBinderReceivedListenerSticky(shizukuBinder);
        Shizuku.addRequestPermissionResultListener(shizukuPermission);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinder);
        Shizuku.removeRequestPermissionResultListener(shizukuPermission);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlertChannel.ensure(this);
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFY) {
            render();
        }
    }

    private void render() {
        rows.removeAllViews();
        PermissionProbe facts = PermissionProbe.read(this);
        PermissionCopy.Board board = PermissionCopy.present(
                facts.canWrite,
                facts.notifications,
                facts.banner,
                facts.storage,
                facts.battery,
                facts.alarm,
                KeeperStore.alertPops(this),
                ShizukuLink.read(this));
        for (int i = 0; i < board.rows.length; i++) {
            if (i > 0) {
                ui.hairline(rows, 16);
            }
            addRow(board.rows[i]);
        }
    }

    private void addRow(PermissionCopy.Row row) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setMinimumHeight(ui.dp(52));
        block.setGravity(Gravity.CENTER_VERTICAL);
        int side = ui.dp(16);
        int vertical = ui.dp(12);
        block.setPadding(side, vertical, side, vertical);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ui.text(16);
        title.setText(row.title);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        line.addView(title, titleParams);
        TextView value = ui.text(15);
        value.setText(row.value);
        value.setIncludeFontPadding(false);
        value.setGravity(Gravity.END);
        value.setTextColor(toneColor(row.tone));
        if (row.tone == PermissionCopy.Tone.HELD) {
            value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        line.addView(value, wrap());
        if (row.action != PermissionCopy.Action.NONE) {
            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.ic_chevron);
            chevron.setColorFilter(ui.muted(), android.graphics.PorterDuff.Mode.SRC_IN);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(
                    ui.dp(18), ui.dp(18));
            chevronParams.setMarginStart(ui.dp(4));
            line.addView(chevron, chevronParams);
        }
        block.addView(line, matchWrap());

        if (!row.hint.isEmpty()) {
            TextView hint = ui.text(13);
            hint.setText(row.hint);
            hint.setTextColor(ui.muted());
            hint.setIncludeFontPadding(false);
            hint.setLineSpacing(0f, 1.3f);
            LinearLayout.LayoutParams hintParams = matchWrap();
            hintParams.topMargin = ui.dp(4);
            block.addView(hint, hintParams);
        }

        if (row.action != PermissionCopy.Action.NONE) {
            TypedValue selectable = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true);
            if (selectable.resourceId != 0) {
                Drawable ripple = getDrawable(selectable.resourceId);
                if (ripple != null) {
                    block.setForeground(ripple);
                }
            }
            block.setClickable(true);
            block.setOnClickListener(view -> open(row.action));
        }
        rows.addView(block, matchWrap());
    }

    private void open(PermissionCopy.Action action) {
        switch (action) {
            case NOTIFY:
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFY);
                    return;
                }
                start(appNotificationSettings());
                return;
            case CHANNEL:
                AlertChannel.ensure(this);
                start(channelSettings());
                return;
            case STORAGE:
                start(storageSettings());
                return;
            case BATTERY:
                start(batterySettings());
                return;
            case ALARM:
                if (Build.VERSION.SDK_INT >= 31) {
                    start(alarmSettings());
                }
                return;
            case STARTUP:
                openStartup();
                return;
            case SHIZUKU:
                openShizuku();
                return;
            case NONE:
            default:
                return;
        }
    }

    private Intent appNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        return intent;
    }

    private Intent channelSettings() {
        Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        intent.putExtra(Settings.EXTRA_CHANNEL_ID, AlertNote.CHANNEL_ID);
        return intent;
    }

    private Intent storageSettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        intent.setData(Uri.parse("package:" + getPackageName()));
        return intent;
    }

    private Intent batterySettings() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        return intent;
    }

    private Intent alarmSettings() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
        intent.setData(Uri.parse("package:" + getPackageName()));
        return intent;
    }

    private void openShizuku() {
        if (ShizukuLink.read(this) == PermissionCopy.ShizukuState.DENIED) {
            try {
                Shizuku.requestPermission(REQUEST_SHIZUKU);
                return;
            } catch (RuntimeException error) {
                Log.w(KeeperEngine.TAG, "shizuku request failed", error);
            }
        }
        Intent intent = new Intent();
        intent.setClassName(ShizukuLink.MANAGER, "moe.shizuku.manager.MainActivity");
        start(intent);
    }

    private void openStartup() {
        for (String[] target : STARTUP) {
            Intent intent = new Intent();
            intent.setClassName(target[0], target[1]);
            if (start(intent)) {
                return;
            }
        }
        Log.w(KeeperEngine.TAG, "startup settings unavailable");
    }

    private boolean start(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException error) {
            Log.w(KeeperEngine.TAG, "settings unavailable", error);
            return false;
        }
    }

    private int toneColor(PermissionCopy.Tone tone) {
        switch (tone) {
            case HELD:
                return ui.held();
            case WAITING:
                return ui.waiting();
            case NEUTRAL:
            default:
                return ui.ink();
        }
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
