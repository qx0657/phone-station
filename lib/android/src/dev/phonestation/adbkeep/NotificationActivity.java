package dev.phonestation.adbkeep;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 两个方向独立设置：手机应用通知同步到 Mac；电脑会话提醒发到手机。 */
public final class NotificationActivity extends Activity {
    private StationChrome ui;
    private StationChrome.Control alert;
    private TextView soundValue;
    private TextView displayValue;
    private boolean updatingAlert;
    private StationChrome.Control sync;
    private StationChrome.Link access;
    private TextView appsValue;
    private TextView syncStatus;
    private boolean painting;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { refreshSync(); handler.postDelayed(this, 2_000); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new StationChrome(this);
        ui.back("通知");

        LinearLayout phone = ui.card();
        ui.groupTitle(phone, "手机通知 → Mac");
        sync = ui.switchRow(phone, R.drawable.ic_status_notification_sync, "同步到 Mac");
        sync.toggle.setOnCheckedChangeListener((button, on) -> {
            if (painting) { return; }
            PhoneNotifications.configure(this, Json.obj().put("enabled", on));
            if (on) {
                KeeperStore.setMcpEnabled(this, true);
                FileMcpService.start(this);
                rebindListener();
            }
            refreshSync();
        });
        ui.hairline(phone, 62);
        access = ui.statusLinkRow(phone, R.drawable.ic_status_permission, "通知使用权", view -> openAccess());
        ui.hairline(phone, 62);
        appsValue = ui.linkRow(phone, R.drawable.ic_status_notify, "选择应用",
                view -> startActivity(new Intent(this, NotificationAppsActivity.class)));
        syncStatus = ui.paragraph(phone, "");
        ui.paragraph(phone, "只同步已选应用的新通知与内容更新，跳过常驻通知和分组汇总。Mac 需运行手机工位，并在「手机通知」页允许接收。");

        LinearLayout card = ui.card();
        ui.groupTitle(card, "电脑提醒 → 手机");
        ui.paragraph(card, "电脑脚本与 AI 会话发来的提醒。以下设置只影响这类提醒在手机上的显示和铃声。");
        displayValue = ui.linkRow(card, R.drawable.ic_status_notify, "显示方式", view -> pickDisplay());
        displayValue.setMaxWidth(ui.dp(148));
        ui.hairline(card, 62);
        alert = ui.switchRow(card, R.drawable.ic_status_notify, "提醒弹出");
        alert.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updatingAlert) {
                return;
            }
            KeeperStore.setAlertPops(this, checked);
            AlertChannel.ensure(this);
            ui.paintOn(alert.mark, checked);
        });
        updatingAlert = true;
        alert.toggle.setChecked(KeeperStore.alertPops(this));
        updatingAlert = false;
        ui.paintOn(alert.mark, alert.toggle.isChecked());

        ui.hairline(card, 62);
        soundValue = ui.linkRow(card, R.drawable.ic_status_tone, "铃声", view -> pickSound());
        soundValue.setMaxWidth(ui.dp(148));
        soundValue.setEllipsize(TextUtils.TruncateAt.END);
        ui.paragraph(card, "显示方式从下一条电脑提醒开始生效。");
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlertChannel.ensure(this);
        updatingAlert = true;
        alert.toggle.setChecked(KeeperStore.alertPops(this));
        updatingAlert = false;
        ui.paintOn(alert.mark, alert.toggle.isChecked());
        refreshDisplay();
        soundValue.setText(soundLabel(KeeperStore.alertSound(this)));
        rebindListener();
        handler.post(refresh);
    }

    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    private void refreshSync() {
        Json state = PhoneNotifications.status(this);
        boolean on = state.get("enabled").boolValue();
        boolean granted = state.get("accessGranted").boolValue();
        int count = (int) state.get("selectedCount").longValue();
        painting = true;
        sync.toggle.setChecked(on);
        painting = false;
        ui.paintOn(sync.mark, on);
        access.value.setText(granted ? "已授权" : "未授权");
        ui.paintOn(access.mark, granted);
        appsValue.setText(count == 0 ? "未选择" : count + " 个");
        syncStatus.setText(!on ? "同步已关闭，可先选择应用"
                : !granted ? "请点「通知使用权」，在系统设置中允许手机工位读取通知"
                : count == 0 ? "请点「选择应用」，勾选要同步的应用"
                : !state.get("listenerConnected").boolValue() ? "等待系统连接通知服务；可重新检查通知使用权"
                : !state.get("macOnline").boolValue() ? "等待 Mac 接收；断线期间不补发旧通知"
                : "Mac 正在接收 · 已选 " + count + " 个应用");
    }

    private void rebindListener() {
        if (PhoneNotifications.enabled(this) && PhoneNotifications.accessGranted(this)
                && !PhoneNotifications.status(this).get("listenerConnected").boolValue()) {
            NotificationListenerService.requestRebind(new ComponentName(this, PhoneNotificationListener.class));
        }
    }

    private void openAccess() {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        new ComponentName(this, PhoneNotificationListener.class).flattenToString());
        try { startActivity(intent); }
        catch (ActivityNotFoundException unsupported) { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
    }

    private void refreshDisplay() {
        displayValue.setText(KeeperStore.alertStacks(this) ? "每条都显示" : "只显示最新一条");
    }

    private void pickDisplay() {
        new AlertDialog.Builder(this)
                .setTitle("电脑提醒显示方式")
                .setSingleChoiceItems(new String[] {"只显示最新一条", "每条都显示"},
                        KeeperStore.alertStacks(this) ? 1 : 0, (dialog, which) -> {
                            KeeperStore.setAlertStacks(this, which == 1);
                            refreshDisplay();
                            dialog.dismiss();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickSound() {
        startActivity(new Intent(this, AlertToneActivity.class));
    }

    private String soundLabel(String stored) {
        if (AlertSoundPlan.SILENT_TOKEN.equals(stored)) {
            return "静音";
        }
        if (stored == null || stored.isEmpty()) {
            return "跟随系统";
        }
        try {
            Ringtone tone = RingtoneManager.getRingtone(this, Uri.parse(stored));
            if (tone == null) {
                return "找不到";
            }
            String title = tone.getTitle(this);
            if (title == null || title.isEmpty()) {
                return "已选择";
            }
            return title;
        } catch (Exception error) {
            return "找不到";
        }
    }
}
