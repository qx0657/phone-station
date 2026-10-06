package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ConnectionActivity extends StationActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private StationChrome ui;
    private StationChrome.Control keep;
    private StationChrome.Control wireless;
    private StationChrome.Notice notice;
    private StationChrome.Link remoteLink;
    private TextView usbSetting;
    private StationChrome.Link checksLink;
    private LinearLayout checksRows;
    private TextView wifiState;
    private TextView localState;
    private TextView mcpState;
    private TextView remoteState;
    private TextView writeState;
    private boolean checksExpanded;
    private String manualMessage = "";
    private boolean wirelessBusy;
    private int wirelessChange;
    private boolean updatingSwitch;
    private boolean updatingWireless;
    private boolean active;
    private final Runnable connectionChanged = () -> { if (active) { render(); } };
    private final Runnable refresh = new Runnable() {
        public void run() { if (active) { render(); handler.postDelayed(this, 1_000L); } }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Keep older notifications and callers pointing at the service's new home.
        if ("mcp".equals(getIntent().getStringExtra(MainActivity.EXTRA_PAGE))) {
            startActivity(new Intent(this, McpHelpActivity.class));
            finish();
            return;
        }
        checksExpanded = saved != null && saved.getBoolean("checks_expanded", false);
        ui = new StationChrome(this);
        ui.back("连接设置");
        LinearLayout link = ui.card();
        ui.groupTitle(link, "本地连接");
        wireless = ui.switchRow(link, R.drawable.ic_status_wireless, "无线调试");
        wireless.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingWireless) { changeWireless(checked); }
        });
        ui.hairline(link, 62);
        keep = ui.switchRow(link, R.drawable.ic_status_keep, "自动保持无线调试");
        keep.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updatingSwitch) {
                return;
            }
            manualMessage = "";
            if (!KeeperStore.setEnabled(this, checked)) {
                manualMessage = "自动保持未能保存，请重试。";
                render();
                return;
            }
            if (checked) {
                KeeperStore.setFailures(this, 0);
                KeeperStore.setLastAttemptMs(this, 0L);
            }
            if (checked && !KeeperEngine.canWrite(this)) { startActivity(new Intent(this, PermissionActivity.class)); }
            if (checked) {
                KeeperService.start(this);
            } else {
                stopKeeping();
            }
            render();
        });
        ui.paragraph(link, "掉线后自动尝试恢复；手动关闭无线调试会同时关闭自动保持。");
        ui.hairline(link, 16);
        usbSetting = ui.valueRow(link, "USB 调试");
        notice = ui.notice(link);
        notice.view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        LinearLayout remote = ui.card();
        ui.groupTitle(remote, "远程连接");
        remoteLink = ui.statusLinkRow(remote, R.drawable.ic_status_cloud, "远程接入",
                "通过互联网连接，无需无线调试",
                view -> startActivity(new Intent(this, RemoteRelayActivity.class)));

        LinearLayout checks = ui.card();
        checksLink = ui.statusLinkRow(checks, R.drawable.ic_status_keep, "连接检查",
                "网络、通道与权限", view -> {
                    checksExpanded = !checksExpanded;
                    paintChecksExpanded();
                    render();
                });
        checksRows = new LinearLayout(this);
        checksRows.setOrientation(LinearLayout.VERTICAL);
        checks.addView(checksRows, matchWrap());
        ui.hairline(checksRows, 16);
        wifiState = checkRow("Wi-Fi 网络");
        localState = checkRow("本地电脑");
        mcpState = checkRow("本地 MCP");
        remoteState = checkRow("远程通道");
        writeState = checkRow("调试写入权限");
        ui.hairline(checksRows, 16);
        ui.statusLinkRow(checksRows, R.drawable.ic_status_mcp, "MCP 服务",
                view -> startActivity(new Intent(this, McpHelpActivity.class)));
        ui.statusLinkRow(checksRows, R.drawable.ic_status_permission, "权限与检查",
                view -> startActivity(new Intent(this, PermissionActivity.class)));
        paintChecksExpanded();
        render();
    }

    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putBoolean("checks_expanded", checksExpanded);
        super.onSaveInstanceState(saved);
    }

    @Override protected void onResume() {
        super.onResume(); if (isFinishing()) { return; }
        active = true; StationConnectionEvents.add(connectionChanged);
        render(); handler.postDelayed(refresh, 1_000L);
    }
    @Override protected void onPause() {
        active = false; StationConnectionEvents.remove(connectionChanged); handler.removeCallbacks(refresh);
        super.onPause();
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }

    private void stopKeeping() {
        stopService(new Intent(this, KeeperService.class));
        KeeperAlarm.cancel(this);
        StationNotifications.connectionChanged();
    }
    private void changeWireless(boolean enabled) {
        if (wirelessBusy) { render(); return; }
        manualMessage = "";
        WirelessControl.Result result = KeeperEngine.setWireless(this, enabled);
        if (result != WirelessControl.Result.APPLIED) {
            switch (result) {
                case NO_PERMISSION: manualMessage = "还不能修改无线调试。请在电脑上重新安装，授予写入权限。"; break;
                case ADB_OFF: manualMessage = "先在系统开发者选项中打开 USB 调试。"; break;
                case NO_WIFI: manualMessage = "先连接 Wi-Fi，再打开无线调试。"; break;
                case STORE_FAILED: manualMessage = "自动保持未能停用，无线调试未关闭。请重试。"; break;
                default: manualMessage = "无线调试未能切换。请检查系统开发者选项后重试。";
            }
            render();
            return;
        }
        if (!enabled) { stopKeeping(); }
        wirelessBusy = true;
        int generation = ++wirelessChange;
        render();
        // 系统可能因网络未获允许把开关拨回去；核对实际值，不自动重做用户操作。
        handler.postDelayed(() -> {
            if (generation != wirelessChange) { return; }
            wirelessBusy = false;
            if (KeeperEngine.wifiAdbEnabled(this) != enabled) {
                manualMessage = enabled
                        ? "无线调试未保持开启。请检查 Wi-Fi，并完成系统的无线调试允许提示。"
                        : "系统仍显示无线调试开启。请到开发者选项中检查。";
            }
            StationNotifications.connectionChanged();
            if (active) { render(); }
        }, KeeperPolicy.CONFIRM_MS);
    }
    private void render() {
        KeeperCopy copy = KeeperEngine.current(this);
        updatingWireless = true;
        wireless.toggle.setChecked(KeeperEngine.wifiAdbEnabled(this));
        updatingWireless = false;
        wireless.setEnabled(StationFeatures.master(this) && !wirelessBusy);
        ui.paintOn(wireless.mark, wireless.toggle.isChecked());
        updatingSwitch = true;
        keep.toggle.setChecked(KeeperStore.isEnabled(this));
        updatingSwitch = false;
        ui.paintOn(keep.mark, keep.toggle.isChecked());
        keep.setEnabled(StationFeatures.master(this) && !wirelessBusy);
        paintNotice(copy);
        paintCheck(usbSetting, "开".equals(copy.usb) ? "已开启" : "已关闭", "开".equals(copy.usb));
        paintRemote();
        if (checksExpanded) { paintChecks(copy); }
    }
    /** 只读诊断项；窄屏和大字号改为上下排列，完整保留名称与状态。 */
    private TextView checkRow(String label) {
        boolean stacked = getResources().getConfiguration().fontScale > 1.3f
                || getResources().getConfiguration().screenWidthDp < 340;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        if (Build.VERSION.SDK_INT >= 28) { row.setScreenReaderFocusable(true); }
        TextView name = ui.text(14);
        name.setText(StationText.translate(label));
        name.setTextColor(ui.muted());
        name.setIncludeFontPadding(false);
        TextView value = ui.text(14);
        value.setIncludeFontPadding(false);
        value.setGravity(stacked ? Gravity.START : Gravity.END);
        if (stacked) {
            row.addView(name, matchWrap());
            value.setPadding(0, dp(4), 0, 0);
            row.addView(value, matchWrap());
        } else {
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMarginStart(dp(12));
            row.addView(value, params);
        }
        checksRows.addView(row, matchWrap());
        return value;
    }
    private void paintChecksExpanded() {
        checksRows.setVisibility(checksExpanded ? View.VISIBLE : View.GONE);
        checksLink.chevron.setRotation(checksExpanded ? 270 : 90);
        checksLink.row.setContentDescription(StationText.translate("连接检查，网络、通道与权限，"
                + (checksExpanded ? "收起" : "展开")));
        checksLink.row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (Build.VERSION.SDK_INT >= 30) {
            checksLink.row.setStateDescription(StationText.translate(checksExpanded ? "已展开" : "已折叠"));
        }
    }
    private void paintChecks(KeeperCopy copy) {
        HostProbe host = HostProbe.current(this);
        boolean master = StationFeatures.master(this);
        paintCheck(wifiState, copy.wifi, "已连接".equals(copy.wifi));
        String local = !host.localConnected ? "未连接"
                : "wireless".equals(host.localTransport) ? "无线已连接"
                : "usb".equals(host.localTransport) ? "USB 已连接" : "已连接";
        paintCheck(localState, local, host.localConnected);
        Json service = FileMcpService.status(this);
        paintCheck(mcpState, service.get("localLabel").string(), service.get("localReady").boolValue());
        paintCheck(remoteState, PhoneRelayClient.connectionLabel(this), master && host.remoteConnected);
        boolean canWrite = KeeperEngine.canWrite(this);
        paintCheck(writeState, canWrite ? "已授权" : "未授权", canWrite);
    }
    private void paintCheck(TextView value, String state, boolean ready) {
        if (!state.contentEquals(value.getText())) { value.setText(StationText.translate(state)); }
        value.setTextColor(ready ? ui.held() : ui.ink());
    }
    private void paintNotice(KeeperCopy copy) {
        String line = manualMessage.isEmpty() ? StationFeatures.master(this) ? copy.notice()
                : "手机工位已暂停。在首页恢复使用后，连接功能会继续运行。" : manualMessage;
        if (line.isEmpty()) {
            notice.view.setVisibility(View.GONE);
            return;
        }
        notice.view.setVisibility(View.VISIBLE);
        notice.view.setText(StationText.translate(line));
        int tone = manualMessage.isEmpty() ? toneColor(copy) : getColor(R.color.error);
        notice.view.setTextColor(tone);
        notice.fill.setColor((tone & 0x00FFFFFF) | 0x1A000000);
    }
    private void paintRemote() {
        boolean enabled = RemoteStore.enabled(this);
        String value = PhoneRelayClient.connectionLabel(this);
        boolean connected = StationFeatures.master(this) && enabled && PhoneRelayClient.connected();
        remoteLink.value.setText(StationText.translate(value));
        remoteLink.value.setTextColor(connected ? ui.held() : ui.muted());
        ui.paintOn(remoteLink.mark, connected);
    }
    private int toneColor(KeeperCopy copy) {
        switch (copy.tone) {
            case HELD:
                return ui.held();
            case WAITING:
                return ui.waiting();
            case NEUTRAL:
            default:
                return ui.ink();
        }
    }
    private int dp(float value) {
        return ui.dp(value);
    }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
