package dev.phonestation.adbkeep;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private StationChrome ui;
    private StationDrawer drawer;
    private StationChrome.Badge hero;
    private TextView headline;
    private TextView connectionType;
    private View connectionBlock;
    private StationChrome.Control keep;
    private StationChrome.Control wireless;
    private StationChrome.Notice notice;
    private ConnectionStatus wifiStatus;
    private ConnectionStatus usbStatus;
    private StationChrome.Control mcp;
    private TextView remoteValue;
    private TextView mcpValue;
    private TextView notificationValue;
    private TextView permissionValue;
    private TextView permissionHint;
    private String manualMessage = "";
    private boolean wirelessBusy;
    private int wirelessChange;
    private String shownConnection;
    private int connectionFade;
    private boolean updatingSwitch;
    private boolean updatingWireless;
    private boolean updatingMcp;
    private Boolean mcpHeld;
    private long mcpHeldAt;
    private boolean active;
    private final Runnable connectionChanged = () -> { if (active) { render(); } };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!active) {
                return;
            }
            render();
            handler.postDelayed(this, 2_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        InstallRecovery.activity(getIntent().getStringExtra(InstallRecovery.EXTRA));
        ui = new StationChrome(this);
        drawer = new StationDrawer(ui);

        LinearLayout link = ui.card();
        connectionBlock = new LinearLayout(this);
        LinearLayout header = (LinearLayout) connectionBlock;
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(72));
        header.setPadding(dp(16), dp(10), dp(16), dp(6));
        hero = ui.disc(R.drawable.ic_status_computer);
        header.addView(hero.plate, new LinearLayout.LayoutParams(dp(48), dp(48)));
        headline = ui.text(26);
        headline.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headline.setIncludeFontPadding(false);
        headline.setMaxLines(2);
        if (Build.VERSION.SDK_INT >= 28) {
            headline.setAccessibilityHeading(true);
        }
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(headline, matchWrap());
        connectionType = ui.text(14);
        connectionType.setTextColor(ui.muted());
        heading.addView(connectionType, matchWrap());
        LinearLayout.LayoutParams headlineParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headlineParams.setMarginStart(dp(14));
        header.addView(heading, headlineParams);
        link.addView(header, matchWrap());

        ui.hairline(link, 16);
        wireless = ui.switchRow(link, R.drawable.ic_status_wireless, "无线调试");
        wireless.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingWireless) { changeWireless(checked); }
        });
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
            if (checked) {
                KeeperService.start(this);
            } else {
                stopKeeping();
            }
            render();
        });
        notice = ui.notice(link);
        notice.view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        permissionHint = ui.linkRow(link, R.drawable.ic_status_permission, "权限需要处理",
                view -> startActivity(new Intent(this, PermissionActivity.class)));
        addConnectionStatus(link);
        LinearLayout services = ui.card();
        ui.groupTitle(services, "MCP 服务");
        mcp = ui.switchRow(services, R.drawable.ic_status_mcp, "启用服务");
        mcp.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updatingMcp) {
                return;
            }
            mcpHeld = checked;
            mcpHeldAt = SystemClock.elapsedRealtime();
            KeeperStore.setMcpEnabled(this, checked);
            if (checked) {
                FileMcpService.start(this);
            } else {
                RemoteStore.setEnabled(this, false);
                FileMcpService.stop(this);
            }
            handler.postDelayed(() -> {
                if (active) {
                    render();
                }
            }, 400L);
        });
        ui.hairline(services, 62);
        remoteValue = ui.linkRow(services, R.drawable.ic_status_wireless, "远程通道",
                view -> startActivity(new Intent(this, RemoteRelayActivity.class)));
        ui.hairline(services, 62);
        mcpValue = ui.linkRow(services, R.drawable.ic_status_mcp, "接入信息与说明",
                view -> startActivity(new Intent(this, McpHelpActivity.class)));

        notificationValue = drawer.destination(R.drawable.ic_status_notify, "通知", NotificationActivity.class);
        drawer.destination(R.drawable.ic_status_clipboard, "共享剪贴板", ClipboardActivity.class);
        permissionValue = drawer.destination(R.drawable.ic_status_permission, "权限", PermissionActivity.class);
        TextView aboutValue = drawer.destination(R.drawable.ic_status_about, "关于", AboutActivity.class);
        aboutValue.setText(versionName());
        aboutValue.setTextColor(ui.muted());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        AlertChannel.ensure(this);
        if (KeeperStore.isEnabled(this)) {
            KeeperService.start(this);
        }
        if (KeeperStore.mcpEnabled(this) || RemoteStore.enabled(this)) {
            FileMcpService.start(this);
        }
        render();
        if (savedInstanceState != null && savedInstanceState.getBoolean("drawer_open")) {
            drawer.restoreOpen();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        InstallRecovery.activity(intent.getStringExtra(InstallRecovery.EXTRA));
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("drawer_open", drawer.isVisible());
        super.onSaveInstanceState(state);
    }

    @Override
    public void onBackPressed() {
        if (drawer.isVisible()) { drawer.close(); }
        else { super.onBackPressed(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        StationConnectionEvents.add(connectionChanged);
        AlertChannel.ensure(this);
        if (KeeperStore.isEnabled(this)) {
            KeeperService.start(this);
        }
        if (KeeperStore.mcpEnabled(this) || RemoteStore.enabled(this)) {
            FileMcpService.start(this);
        }
        StationNotifications.restore(this);
        render();
        handler.postDelayed(refresh, 2_000L);
    }

    @Override
    protected void onPause() {
        active = false;
        StationConnectionEvents.remove(connectionChanged);
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        drawer.dispose();
        super.onDestroy();
    }

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
        HostProbe host = HostProbe.current(this);
        paintConnection(host);
        KeeperCopy copy = KeeperEngine.current(this);
        updatingWireless = true;
        wireless.toggle.setChecked(KeeperEngine.wifiAdbEnabled(this));
        updatingWireless = false;
        wireless.setEnabled(KeeperEngine.canWrite(this) && !wirelessBusy);
        ui.paintOn(wireless.mark, wireless.toggle.isChecked());
        updatingSwitch = true;
        keep.toggle.setChecked(KeeperStore.isEnabled(this));
        updatingSwitch = false;
        ui.paintOn(keep.mark, keep.toggle.isChecked());
        keep.setEnabled(KeeperEngine.canWrite(this) && !wirelessBusy);
        wifiStatus.paint("已连接".equals(copy.wifi), copy.wifi);
        usbStatus.paint("开".equals(copy.usb), "开".equals(copy.usb) ? "已开启" : "已关闭");
        paintNotice(copy);
        paintMcp();
        paintRemote();
        paintNotification();
        paintPermissions();
    }

    private void addConnectionStatus(LinearLayout card) {
        // 底边直接接入卡片，避免把只读状态做成另一个可点击设置。
        card.setPadding(0, dp(6), 0, 0);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setMinimumHeight(dp(64));
        strip.setPadding(dp(16), dp(10), dp(16), dp(12));
        strip.setBackgroundColor(getColor(R.color.connection_strip));
        wifiStatus = new ConnectionStatus(strip, R.drawable.ic_status_wifi, "Wi-Fi");
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.hairline));
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(dp(1), dp(28));
        line.setMarginStart(dp(12));
        line.setMarginEnd(dp(16));
        strip.addView(divider, line);
        usbStatus = new ConnectionStatus(strip, R.drawable.ic_status_usb, "USB 调试");
        card.addView(strip, matchWrap());
    }

    private final class ConnectionStatus {
        private final LinearLayout cell;
        private final ImageView icon;
        private final TextView value;
        private final String label;

        ConnectionStatus(LinearLayout strip, int drawable, String label) {
            this.label = label;
            cell = new LinearLayout(MainActivity.this);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            icon = new ImageView(MainActivity.this);
            icon.setImageResource(drawable);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
            LinearLayout copy = new LinearLayout(MainActivity.this);
            copy.setOrientation(LinearLayout.VERTICAL);
            TextView name = ui.text(12);
            name.setText(label);
            name.setTextColor(ui.muted());
            name.setIncludeFontPadding(false);
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            copy.addView(name, matchWrap());
            value = ui.text(14);
            value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            value.setIncludeFontPadding(false);
            value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams valueParams = matchWrap();
            valueParams.topMargin = dp(2);
            copy.addView(value, valueParams);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.setMarginStart(dp(10));
            cell.addView(copy, textParams);
            strip.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        void paint(boolean on, String state) {
            icon.setColorFilter(on ? ui.held() : ui.muted(), PorterDuff.Mode.SRC_IN);
            value.setText(state);
            value.setTextColor(on ? ui.ink() : ui.muted());
            cell.setContentDescription(label + "，" + state);
        }
    }

    private void paintNotification() {
        boolean pops = KeeperStore.alertPops(this);
        notificationValue.setText(pops ? "弹出" : "不弹出");
        notificationValue.setTextColor(pops ? ui.held() : ui.muted());
    }

    private void paintPermissions() {
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
        permissionValue.setText(board.summary);
        permissionValue.setTextColor(board.summaryTone == PermissionCopy.Tone.HELD
                ? ui.held()
                : ui.waiting());
        permissionHint.setText(board.summary);
        permissionHint.setTextColor(ui.waiting());
        ((View) permissionHint.getParent()).setVisibility(
                board.summaryTone == PermissionCopy.Tone.HELD ? View.GONE : View.VISIBLE);
    }

    private void paintConnection(HostProbe host) {
        int color = host.linked ? ui.held() : ui.ink();
        String shown = host.headline + "\n" + host.connectionType;
        if (shown.equals(shownConnection)) {
            headline.setTextColor(color);
            paintDisc(host.linked);
            return;
        }
        boolean first = shownConnection == null;
        shownConnection = shown;
        Runnable apply = () -> {
            headline.setText(host.headline);
            headline.setTextColor(color);
            connectionType.setText(host.connectionType);
            connectionType.setVisibility(host.connectionType.isEmpty() ? View.GONE : View.VISIBLE);
            paintDisc(host.linked);
        };
        if (first) {
            apply.run();
            return;
        }
        fade(connectionBlock, apply);
    }

    private void paintDisc(boolean linked) {
        if (linked) {
            hero.fill.setColor(ui.held());
            hero.icon.setColorFilter(ui.night ? getColor(R.color.page) : Color.WHITE, PorterDuff.Mode.SRC_IN);
            return;
        }
        hero.fill.setColor(getColor(R.color.well));
        hero.icon.setColorFilter(ui.ink(), PorterDuff.Mode.SRC_IN);
    }

    private void paintNotice(KeeperCopy copy) {
        String line = manualMessage.isEmpty() ? copy.notice() : manualMessage;
        if (line.isEmpty()) {
            notice.view.setVisibility(View.GONE);
            return;
        }
        notice.view.setVisibility(View.VISIBLE);
        notice.view.setText(line);
        int tone = manualMessage.isEmpty() ? toneColor(copy) : getColor(R.color.error);
        notice.view.setTextColor(tone);
        notice.fill.setColor((tone & 0x00FFFFFF) | 0x1A000000);
    }

    private void fade(View block, Runnable apply) {
        int generation = ++connectionFade;
        block.animate().cancel();
        block.animate().alpha(0f).setDuration(90).withEndAction(() -> {
            if (generation != connectionFade) {
                return;
            }
            apply.run();
            block.setAlpha(0f);
            block.animate().alpha(1f).setDuration(140).start();
        }).start();
    }

    private void paintMcp() {
        boolean live = FileMcpService.listening();
        if (mcpHeld != null
                && (mcpHeld == live || SystemClock.elapsedRealtime() - mcpHeldAt > 3_000L)) {
            mcpHeld = null;
        }
        boolean wanted = KeeperStore.mcpEnabled(this);
        boolean shown = mcpHeld != null ? mcpHeld : (live || wanted);
        updatingMcp = true;
        mcp.toggle.setChecked(shown);
        updatingMcp = false;
        ui.paintOn(mcp.mark, shown);
        mcpValue.setText(live ? "运行中" : wanted ? "启动中" : "已关闭");
        mcpValue.setTextColor(live ? ui.held() : ui.muted());
    }

    private void paintRemote() {
        boolean enabled = RemoteStore.enabled(this);
        String value = !RemoteStore.configured(this)
                ? "未配置"
                : (!enabled ? "已关闭" : (PhoneRelayClient.connected() ? "已连接" : "连接中"));
        remoteValue.setText(value);
        remoteValue.setTextColor(enabled && PhoneRelayClient.connected() ? ui.held() : ui.muted());
        remoteValue.setVisibility(View.VISIBLE);
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

    private String versionName() {
        try {
            return AboutCopy.version(
                    getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException error) {
            return AboutCopy.version(null);
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
