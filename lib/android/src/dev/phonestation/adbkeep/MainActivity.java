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
    static final String EXTRA_PAGE = "dev.phonestation.adbkeep.PAGE";
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
    private LinearLayout connectionStatus;
    private StationChrome.Control mcp;
    private StationChrome.Link remoteLink;
    private StationChrome.Link mcpLink;
    private StationChrome.Link clipboardLink;
    private StationChrome.Link notificationLink;
    private TextView permissionValue;
    private LinearLayout healthIssues;
    private View servicesBlock;
    private String shownHealth;
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
            handler.postDelayed(this, 1_000L);
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
        headline.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (Build.VERSION.SDK_INT >= 28) {
            headline.setAccessibilityHeading(true);
        }
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        titleLine.addView(headline, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addConnectionStatus(titleLine);
        heading.addView(titleLine, matchWrap());
        connectionType = ui.text(14);
        connectionType.setTextColor(ui.muted());
        heading.addView(connectionType, matchWrap());
        LinearLayout.LayoutParams headlineParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headlineParams.setMarginStart(dp(14));
        header.addView(heading, headlineParams);
        link.addView(header, matchWrap());

        healthIssues = new LinearLayout(this);
        healthIssues.setOrientation(LinearLayout.VERTICAL);
        healthIssues.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        link.addView(healthIssues, matchWrap());

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

        LinearLayout collaboration = ui.card();
        clipboardLink = ui.statusLinkRow(collaboration, R.drawable.ic_status_clipboard, "共享剪贴板",
                view -> startActivity(new Intent(this, ClipboardActivity.class)));
        ui.paragraph(collaboration, "复制文字或链接，自动同步到另一端。");
        ui.hairline(collaboration, 16);
        notificationLink = ui.statusLinkRow(collaboration, R.drawable.ic_status_notify, "通知",
                view -> startActivity(new Intent(this, NotificationActivity.class)));
        ui.paragraph(collaboration, "手机通知同步到 Mac，电脑提醒发到手机。");

        LinearLayout services = ui.card();
        servicesBlock = services;
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
        remoteLink = ui.statusLinkRow(services, R.drawable.ic_status_wireless, "远程通道",
                view -> startActivity(new Intent(this, RemoteRelayActivity.class)));
        ui.hairline(services, 62);
        mcpLink = ui.statusLinkRow(services, R.drawable.ic_status_mcp, "接入信息与说明",
                view -> startActivity(new Intent(this, McpHelpActivity.class)));

        permissionValue = drawer.destination(R.drawable.ic_status_permission, "权限与检查", PermissionActivity.class);
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
        openRequestedPage();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        InstallRecovery.activity(intent.getStringExtra(InstallRecovery.EXTRA));
        openRequestedPage();
    }

    private void openRequestedPage() {
        String page = getIntent().getStringExtra(EXTRA_PAGE);
        getIntent().removeExtra(EXTRA_PAGE);
        if ("permissions".equals(page)) { startActivity(new Intent(this, PermissionActivity.class)); }
        else if ("clipboard".equals(page)) { startActivity(new Intent(this, ClipboardActivity.class)); }
        else if ("mcp".equals(page)) {
            ui.scroll.post(() -> ui.scroll.smoothScrollTo(0, servicesBlock.getTop()));
        }
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
        handler.postDelayed(refresh, 1_000L);
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
        String status = wifiStatus.description() + "；" + usbStatus.description();
        connectionStatus.setContentDescription(status);
        connectionStatus.setTooltipText(status);
        paintNotice(copy);
        paintMcp();
        paintRemote();
        paintClipboard();
        paintNotification();
        paintPermissions();
        paintHealth();
    }

    private void addConnectionStatus(LinearLayout titleLine) {
        // 只读状态跟随连接标题，不占一整行，也不加设置入口的底托。
        connectionStatus = new LinearLayout(this);
        connectionStatus.setOrientation(LinearLayout.HORIZONTAL);
        connectionStatus.setGravity(Gravity.CENTER_VERTICAL);
        connectionStatus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        wifiStatus = new ConnectionStatus(R.drawable.ic_status_wifi,
                R.drawable.ic_status_wifi_off, "Wi-Fi");
        usbStatus = new ConnectionStatus(R.drawable.ic_status_usb,
                R.drawable.ic_status_usb_off, "USB 调试");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        params.setMarginStart(dp(12));
        titleLine.addView(connectionStatus, params);
    }

    private final class ConnectionStatus {
        private final ImageView icon;
        private final int onDrawable;
        private final int offDrawable;
        private final String label;
        private String state;
        private Boolean shownOn;

        ConnectionStatus(int onDrawable, int offDrawable, String label) {
            this.onDrawable = onDrawable;
            this.offDrawable = offDrawable;
            this.label = label;
            icon = new ImageView(MainActivity.this);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(22), dp(22));
            if (connectionStatus.getChildCount() > 0) {
                params.setMarginStart(dp(12));
            }
            connectionStatus.addView(icon, params);
        }

        void paint(boolean on, String state) {
            this.state = state;
            if (shownOn == null || shownOn != on) {
                icon.setImageResource(on ? onDrawable : offDrawable);
                shownOn = on;
            }
            icon.setColorFilter(on ? ui.held() : ui.muted(), PorterDuff.Mode.SRC_IN);
        }

        String description() {
            return label + "，" + state;
        }
    }

    private void paintNotification() {
        Json state = PhoneNotifications.status(this);
        boolean on = state.get("enabled").boolValue();
        boolean granted = state.get("accessGranted").boolValue();
        int count = (int) state.get("selectedCount").longValue();
        boolean listener = state.get("listenerConnected").boolValue();
        boolean mac = state.get("macOnline").boolValue();
        boolean mcpRunning = FileMcpService.listening();
        boolean ready = on && granted && count > 0 && listener && mac && mcpRunning;
        String value = !on ? "同步未开" : !granted ? "未授权" : count == 0 ? "未选应用"
                : !mcpRunning ? "服务未开" : !listener ? "等待服务" : !mac ? "待 Mac 接收" : "同步中";
        String description = !on ? "手机通知同步已关闭；可设置电脑提醒"
                : !granted ? "手机通知同步未授予通知使用权"
                : count == 0 ? "手机通知同步尚未选择应用"
                : !mcpRunning ? "手机通知同步需要启用 MCP 服务"
                : !listener ? "等待系统连接通知服务"
                : !mac ? "手机通知等待 Mac 接收"
                : "Mac 正在接收手机通知，已选 " + count + " 个应用";
        notificationLink.value.setText(value);
        boolean needsAction = on && (!granted || count == 0 || !mcpRunning || !listener);
        notificationLink.value.setTextColor(ready ? ui.held() : needsAction ? ui.waiting() : ui.muted());
        notificationLink.value.setContentDescription(description);
        ui.paintOn(notificationLink.mark, ready);
    }

    private void paintClipboard() {
        Json state = SharedClipboard.health(this);
        boolean shared = state.get("shared").boolValue();
        boolean ready = state.get("ready").boolValue() && state.get("automatic").boolValue();
        String title = state.get("status").string();
        String action = state.get("action").string();
        String value = !shared ? "已关闭" : ready ? "同步中"
                : "clipboard".equals(action) ? "待 Mac 接入"
                : "none".equals(action) ? (title.contains("已跳过") ? "已跳过" : "已暂停") : "需检查";
        clipboardLink.value.setText(value);
        boolean needsAction = shared && ("permissions".equals(action) || "mcp".equals(action));
        clipboardLink.value.setTextColor(ready ? ui.held() : needsAction ? ui.waiting() : ui.muted());
        clipboardLink.value.setContentDescription(title);
        ui.paintOn(clipboardLink.mark, ready);
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
    }

    private void paintHealth() {
        Json issues = HealthStatus.attention(PhoneHealth.read(this).get("issues"));
        String fingerprint = issues.emit();
        if (fingerprint.equals(shownHealth)) { return; }
        shownHealth = fingerprint;
        healthIssues.removeAllViews();
        healthIssues.setVisibility(issues.array().isEmpty() ? View.GONE : View.VISIBLE);
        int count = issues.array().size();
        if (count > 0) {
            ui.hairline(healthIssues, 16);
            String first = issues.array().get(0).get("title").string();
            ui.issueRow(healthIssues, "有 " + count + " 项需要处理",
                    count == 1 ? first : first + "等，查看处理方法",
                    view -> startActivity(new Intent(this, PermissionActivity.class)));
        }
    }

    private void paintConnection(HostProbe host) {
        int color = host.linked ? ui.held() : host.remoteChecking ? ui.waiting() : ui.ink();
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
        mcpLink.value.setText(live ? "运行中" : wanted ? "启动中" : "已关闭");
        mcpLink.value.setTextColor(live ? ui.held() : ui.muted());
        ui.paintOn(mcpLink.mark, live);
    }

    private void paintRemote() {
        boolean enabled = RemoteStore.enabled(this);
        String value = PhoneRelayClient.connectionLabel(this);
        boolean connected = enabled && PhoneRelayClient.connected();
        remoteLink.value.setText(value);
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
