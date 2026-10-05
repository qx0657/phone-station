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
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** 两个方向独立设置：手机应用通知同步到 Mac；电脑会话提醒发到手机。 */
public final class NotificationActivity extends Activity {
    private StationChrome ui;
    private Switch receiveAlerts;
    private TextView alertsStatus;
    private StationChrome.Control alert;
    private TextView soundValue;
    private TextView displayValue;
    private boolean updatingAlert;
    private Switch sync;
    private StationChrome.Link access;
    private StationChrome.Link mcpAccess;
    private TextView appsValue;
    private TextView syncStatus;
    private android.widget.Button remoteSync;
    private StationChrome.Link alertAccess;
    private StationChrome.Link remoteAlerts;
    private TextView pauseNotice;
    private TextView syncFeedback;
    private TextView alertsFeedback;
    private StationChrome.Disclosure help;
    private boolean saving;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
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
        pauseNotice = ui.paragraph(ui.column, "手机工位已暂停，修改的设置将在恢复使用后生效。");

        LinearLayout phone = ui.card();
        sync = ui.groupSwitch(phone, "手机通知 → Mac", "同步手机通知到 Mac");
        sync.setOnCheckedChangeListener((button, on) -> {
            if (!painting && !saving) { saveFeature("notifications", on, syncFeedback); }
        });
        syncFeedback = ui.paragraph(phone, "");
        syncFeedback.setVisibility(android.view.View.GONE);
        syncFeedback.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        access = ui.statusLinkRow(phone, R.drawable.ic_status_permission, "通知使用权", view -> openAccess());
        appsValue = ui.linkRow(phone, R.drawable.ic_status_notify, "选择应用",
                view -> startActivity(new Intent(this, NotificationAppsActivity.class)));
        syncStatus = ui.paragraph(phone, "");
        mcpAccess = ui.statusLinkRow(phone, R.drawable.ic_status_mcp, "查看 MCP 服务",
                view -> startActivity(new Intent(this, McpHelpActivity.class)));
        remoteSync = ui.action(phone, "允许远程访问通知", false,
                view -> startActivity(RemotePermissionsActivity.intent(this, "personal")));

        LinearLayout card = ui.card();
        receiveAlerts = ui.groupSwitch(card, "电脑提醒 → 手机", "接收电脑提醒");
        receiveAlerts.setOnCheckedChangeListener((button, on) -> {
            if (!painting && !saving) { saveFeature("alerts", on, alertsFeedback); }
        });
        alertsFeedback = ui.paragraph(card, "");
        alertsFeedback.setVisibility(android.view.View.GONE);
        alertsFeedback.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        alertsStatus = ui.paragraph(card, "");
        alertAccess = ui.statusLinkRow(card, R.drawable.ic_status_permission, "允许显示通知",
                v -> startActivity(new Intent(this, PermissionActivity.class).putExtra("feature", "alerts")));
        remoteAlerts = ui.statusLinkRow(card, R.drawable.ic_status_permission, "允许远程提醒",
                v -> startActivity(RemotePermissionsActivity.intent(this, "controls")));
        displayValue = ui.linkRow(card, R.drawable.ic_status_notify, "显示方式", view -> pickDisplay());
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
        help = ui.disclosure("使用说明与权限", savedInstanceState != null && savedInstanceState.getBoolean("help_expanded"));
        ui.paragraph(help.body, "手机通知只同步已选应用的新通知与内容更新，跳过常驻通知和分组汇总；断线后不补发旧通知。Mac 需运行手机工位，并在「手机通知」页允许接收。");
        ui.paragraph(help.body, "电脑提醒由脚本与 AI 会话发来。显示方式、弹出和铃声只影响这类提醒，从下一条开始生效。");
        ui.linkRow(help.body, R.drawable.ic_status_permission, "手机通知使用权", view -> openAccess());
        ui.linkRow(help.body, R.drawable.ic_status_permission, "电脑提醒显示权限",
                view -> startActivity(new Intent(this, PermissionActivity.class).putExtra("feature", "alerts")));
        ui.linkRow(help.body, R.drawable.ic_status_permission, "远程同步访问",
                view -> startActivity(RemotePermissionsActivity.intent(this, "personal")));
        ui.linkRow(help.body, R.drawable.ic_status_permission, "远程提醒访问",
                view -> startActivity(RemotePermissionsActivity.intent(this, "controls")));
        ui.paragraph(help.body, "远程访问范围只决定远程电脑可使用的能力，接收选择由本页开关决定。本地提醒不受远程访问范围影响。");
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
        boolean master = StationFeatures.master(this);
        boolean alerts = StationFeatures.selected(this, "alerts");
        pauseNotice.setVisibility(master ? android.view.View.GONE : android.view.View.VISIBLE);
        painting = true;
        if (!saving) { sync.setChecked(on); receiveAlerts.setChecked(alerts); }
        sync.setEnabled(!saving); receiveAlerts.setEnabled(!saving);
        painting = false;
        String alertsReason = FeatureReadiness.reason(this, "alerts");
        boolean alertProblem = master && alerts && !"已开启".equals(alertsReason);
        alertsStatus.setText(alertsReason.replaceFirst("^已开启 · ", ""));
        alertsStatus.setTextColor(ui.waiting());
        alertsStatus.setVisibility(alertProblem ? android.view.View.VISIBLE : android.view.View.GONE);
        alertAccess.row.setVisibility(master && alerts && !PermissionProbe.read(this).notifications ? android.view.View.VISIBLE : android.view.View.GONE);
        remoteAlerts.row.setVisibility(master && alerts && RemoteStore.enabled(this) && !RemoteStore.permission(this, "controls")
                ? android.view.View.VISIBLE : android.view.View.GONE);
        access.value.setText(granted ? "已授权" : "未授权");
        access.row.setVisibility(master && on && (!granted || !state.get("listenerConnected").boolValue()) ? android.view.View.VISIBLE : android.view.View.GONE);
        appsValue.setText(count == 0 ? "未选择" : count + " 个");
        boolean remoteOnly = HostProbe.remoteOnly(this);
        boolean blocked = remoteOnly && !RemoteStore.permission(this, "personal");
        remoteSync.setVisibility(master && on && blocked ? android.view.View.VISIBLE : android.view.View.GONE);
        boolean mcpReady = FileMcpService.listening() || PhoneRelayClient.connected();
        mcpAccess.row.setVisibility(master && on && !mcpReady ? android.view.View.VISIBLE : android.view.View.GONE);
        syncStatus.setVisibility(master && on ? android.view.View.VISIBLE : android.view.View.GONE);
        syncStatus.setText(!StationFeatures.master(this) ? "总开关已关闭，同步选择已保留" : !on ? "同步已关闭，可先选择应用"
                : !granted ? "请点「通知使用权」，在系统设置中允许手机工位读取通知"
                : count == 0 ? "请点「选择应用」，勾选要同步的应用"
                : !mcpReady ? "接入服务未运行，请查看 MCP 服务"
                : !state.get("listenerConnected").boolValue() ? "等待系统连接通知服务；可重新检查通知使用权"
                : blocked ? "远程通知接收未授权，请在「远程访问范围」中允许「剪贴板与通知」"
                : !state.get("macOnline").boolValue() ? "等待 Mac 接收；断线期间不补发旧通知"
                : "Mac 正在接收");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("help_expanded", help.expanded); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy();
    }
    private void saveFeature(String key, boolean on, TextView feedback) {
        saving = true;
        feedback.setText("正在保存…"); feedback.setTextColor(ui.muted());
        feedback.setVisibility(android.view.View.VISIBLE);
        refreshSync();
        android.content.Context context = getApplicationContext();
        worker.execute(() -> {
            String failure = null;
            try {
                if (!StationFeatures.set(context, key, on)) { failure = "设置未保存，请重试。"; }
            } catch (RuntimeException error) { failure = "设置未完成，请重试并检查当前状态。"; }
            final String error = failure;
            handler.post(() -> {
                saving = false;
                if (isDestroyed()) { return; }
                feedback.setText(error == null ? "" : error);
                feedback.setTextColor(getColor(R.color.error));
                feedback.setVisibility(error == null ? android.view.View.GONE : android.view.View.VISIBLE);
                refreshSync();
                if (error == null && on) {
                    if ("notifications".equals(key)) { rebindListener(); }
                    if (hasWindowFocus()) { FeatureReadiness.prompt(this, key); }
                }
            });
        });
    }

    private void rebindListener() {
        if (StationFeatures.active(this, "notifications") && PhoneNotifications.accessGranted(this)
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
