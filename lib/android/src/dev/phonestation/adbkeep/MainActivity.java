package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Home shows everyday use; connection and permission choices have dedicated pages. */
public final class MainActivity extends Activity {
    static final String EXTRA_PAGE = "dev.phonestation.adbkeep.PAGE";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private StationChrome ui;
    private StationChrome.Badge hero;
    private TextView headline;
    private TextView connectionHint;
    private LinearLayout connectionIndicators;
    private Signal localIndicator;
    private Signal remoteIndicator;
    private Button master;
    private int shownMasterIcon;
    private int shownMasterColor;
    private TextView feedback;
    private boolean savingMaster;
    private StationChrome.Link mcpLink;
    private StationChrome.Link clipboardLink;
    private StationChrome.Link notificationLink;
    private LinearLayout healthIssues;
    private String shownHealth;
    private boolean active;
    private final Runnable connectionChanged = () -> { if (active) { render(); } };
    private final Runnable refresh = new Runnable() {
        public void run() { if (active) { render(); handler.postDelayed(this, 1_000L); } }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        InstallRecovery.activity(getIntent().getStringExtra(InstallRecovery.EXTRA));
        StationFeatures.initialize(this);
        ui = new StationChrome(this);
        ui.homeHeader("手机工位", view -> startActivity(new Intent(this, SettingsActivity.class)));
        boolean narrow = getResources().getConfiguration().fontScale > 1.3f
                || getResources().getConfiguration().screenWidthDp < 340;

        LinearLayout overview = ui.card();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(18), dp(16), dp(12));
        hero = ui.disc(R.drawable.ic_status_computer);
        header.addView(hero.plate, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        headline = ui.text(26);
        headline.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headline.setIncludeFontPadding(false);
        headline.setAccessibilityHeading(true);
        headline.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        words.addView(headline, matchWrap());
        connectionIndicators = new LinearLayout(this);
        connectionIndicators.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        connectionIndicators.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams indicatorParams = matchWrap();
        indicatorParams.topMargin = dp(4);
        words.addView(connectionIndicators, indicatorParams);
        localIndicator = signal(connectionIndicators);
        remoteIndicator = signal(connectionIndicators);
        connectionHint = ui.text(14);
        connectionHint.setTextColor(ui.muted());
        connectionHint.setPadding(0, dp(4), 0, 0);
        words.addView(connectionHint, matchWrap());
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.setMarginStart(dp(14));
        header.addView(words, titleParams);
        overview.addView(header, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(16), dp(4), dp(16), dp(10));
        actions.addView(ui.homeAction("连接设置", R.drawable.ic_status_mcp,
                view -> startActivity(new Intent(this, ConnectionActivity.class))),
                new LinearLayout.LayoutParams(narrow ? -1 : 0, -2, narrow ? 0f : 1f));
        master = ui.homeAction("暂停", R.drawable.ic_pause, view -> changeMaster());
        LinearLayout.LayoutParams masterParams = new LinearLayout.LayoutParams(
                narrow ? -1 : 0, -2, narrow ? 0f : 1f);
        if (narrow) { masterParams.topMargin = dp(8); }
        else { masterParams.setMarginStart(dp(10)); }
        actions.addView(master, masterParams);
        overview.addView(actions, matchWrap());
        feedback = ui.paragraph(overview, "");
        feedback.setVisibility(View.GONE);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        healthIssues = new LinearLayout(this);
        healthIssues.setOrientation(LinearLayout.VERTICAL);
        healthIssues.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        overview.addView(healthIssues, matchWrap());

        LinearLayout collaboration = ui.card();
        mcpLink = ui.statusLinkRow(collaboration, R.drawable.ic_status_mcp, "MCP 服务", "文件、命令、屏幕与手机控制",
                view -> startActivity(new Intent(this, McpHelpActivity.class)));
        ui.hairline(collaboration, 62);
        clipboardLink = ui.statusLinkRow(collaboration, R.drawable.ic_status_clipboard, "共享剪贴板", "复制后，到另一端粘贴",
                view -> startActivity(new Intent(this, ClipboardActivity.class)));
        ui.hairline(collaboration, 62);
        notificationLink = ui.statusLinkRow(collaboration, R.drawable.ic_status_notify, "通知", "手机通知与电脑提醒",
                view -> startActivity(new Intent(this, NotificationActivity.class)));

        render();
        openRequestedPage();
    }

    private void changeMaster() {
        if (savingMaster) { return; }
        boolean on = !StationFeatures.master(this);
        savingMaster = true;
        feedback.setText(on ? "正在恢复…" : "正在暂停…");
        feedback.setTextColor(ui.muted());
        feedback.setVisibility(View.VISIBLE);
        render();
        new Thread(() -> {
            String failure = "";
            try {
                if (!StationFeatures.setMaster(this, on)) { failure = "设置未保存，请重试。"; }
            } catch (RuntimeException error) { failure = "设置已保存，请检查功能状态：" + error.getMessage(); }
            final String result = failure;
            handler.post(() -> {
                savingMaster = false;
                if (isDestroyed()) { return; }
                feedback.setText(result);
                feedback.setTextColor(getColor(R.color.error));
                feedback.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);
                render();
                if (on && result.isEmpty() && hasWindowFocus()) { FeatureReadiness.promptMaster(this); }
            });
        }, "station-master").start();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        InstallRecovery.activity(intent.getStringExtra(InstallRecovery.EXTRA));
        openRequestedPage();
    }

    private void openRequestedPage() {
        String page = getIntent().getStringExtra(EXTRA_PAGE);
        getIntent().removeExtra(EXTRA_PAGE);
        if ("features".equals(page)) { startActivity(new Intent(this, FeaturesActivity.class)); }
        else if ("permissions".equals(page)) { startActivity(new Intent(this, PermissionActivity.class)); }
        else if ("remotePermissions".equals(page)) {
            String scope = getIntent().getStringExtra(RemotePermissionsActivity.EXTRA_SCOPE);
            getIntent().removeExtra(RemotePermissionsActivity.EXTRA_SCOPE);
            startActivity(RemotePermissionsActivity.intent(this, scope));
        }
        else if ("clipboard".equals(page)) { startActivity(new Intent(this, ClipboardActivity.class)); }
        else if ("mcp".equals(page)) {
            startActivity(new Intent(this, McpHelpActivity.class));
        }
    }

    @Override protected void onResume() {
        super.onResume(); active = true; StationConnectionEvents.add(connectionChanged);
        AlertChannel.ensure(this);
        if (KeeperStore.isEnabled(this)) { KeeperService.start(this); }
        if (KeeperStore.mcpEnabled(this) || RemoteStore.enabled(this)) { FileMcpService.start(this); }
        StationNotifications.restore(this);
        render(); handler.postDelayed(refresh, 1_000L);
    }
    @Override protected void onPause() {
        active = false; StationConnectionEvents.remove(connectionChanged); handler.removeCallbacks(refresh);
        super.onPause();
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }

    private void render() {
        boolean enabled = StationFeatures.master(this);
        HostProbe host = HostProbe.current(this);
        headline.setText(enabled ? host.headline : "已暂停");
        headline.setTextColor(enabled && host.linked ? ui.held() : ui.ink());
        connectionIndicators.setVisibility(enabled ? View.VISIBLE : View.GONE);
        String hint = !enabled ? "同步与远程访问已暂停，设置已保留"
                : !host.linked && !host.remoteChecking ? "从电脑连接这台手机，即可开始使用" : "";
        connectionHint.setText(hint);
        connectionHint.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
        paintConnections(host);
        paintDisc(enabled && host.linked);
        master.setText(savingMaster ? "请稍候…" : enabled ? "暂停" : "恢复使用");
        master.setEnabled(!savingMaster);
        master.setContentDescription(enabled ? "暂停手机工位，保留设置" : "恢复手机工位");
        int masterColor = enabled ? ui.ink() : ui.held();
        if (shownMasterColor != masterColor) {
            shownMasterColor = masterColor;
            master.setTextColor(new android.content.res.ColorStateList(new int[][] {
                    new int[] {-android.R.attr.state_enabled}, new int[] {}},
                    new int[] {ui.muted(), masterColor}));
        }
        int masterIcon = enabled ? R.drawable.ic_pause : R.drawable.ic_play;
        if (shownMasterIcon != masterIcon) {
            shownMasterIcon = masterIcon;
            ui.setActionIcon(master, masterIcon);
        }
        paintMcp(); paintClipboard(); paintNotification(); paintHealth();
    }

    private void paintMcp() {
        Json state = FileMcpService.status(this);
        boolean available = state.get("available").boolValue();
        mcpLink.value.setText(state.get("label").string());
        mcpLink.value.setTextColor(state.get("attention").boolValue() ? ui.waiting() : available ? ui.held() : ui.muted());
        mcpLink.value.setContentDescription(state.get("detail").string());
        ui.paintOn(mcpLink.mark, available);
    }

    private Signal signal(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(24));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        ImageView icon = new ImageView(this);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView label = ui.text(14);
        label.setIncludeFontPadding(false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1f);
        labelParams.setMarginStart(dp(6));
        row.addView(label, labelParams);
        boolean stacked = parent.getOrientation() == LinearLayout.VERTICAL;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(stacked ? -1 : 0, -2, stacked ? 0f : 1f);
        if (parent.getChildCount() > 0) {
            if (stacked) { params.topMargin = dp(4); }
            else { params.setMarginStart(dp(12)); }
        }
        parent.addView(row, params);
        return new Signal(row, icon, label);
    }

    private void paintConnections(HostProbe host) {
        boolean usb = "usb".equals(host.localTransport);
        boolean unknown = host.localConnected && !usb && !"wireless".equals(host.localTransport);
        String local = usb ? "USB" : unknown ? "本地" : "无线";
        int localIcon = usb ? (host.localConnected ? R.drawable.ic_status_usb : R.drawable.ic_status_usb_off)
                : unknown ? R.drawable.ic_status_computer
                : host.localConnected ? R.drawable.ic_status_wifi : R.drawable.ic_status_wifi_off;
        paintSignal(localIndicator, localIcon, local, local + "连接" + (host.localConnected ? "已连接" : "未连接"),
                host.localConnected ? ui.held() : ui.muted(), host.localConnected ? ui.held() : ui.muted());
        boolean checking = !host.remoteConnected && host.remoteChecking;
        String remoteLabel = checking ? (connectionIndicators.getOrientation() == LinearLayout.VERTICAL
                ? "远程确认中" : "远程\n确认中") : "远程";
        paintSignal(remoteIndicator, host.remoteConnected || checking ? R.drawable.ic_status_cloud : R.drawable.ic_status_cloud_off,
                remoteLabel,
                "远程连接" + (host.remoteConnected ? "已连接" : checking ? "待确认" : "未连接"),
                host.remoteConnected ? ui.held() : checking ? ui.waiting() : ui.muted(),
                host.remoteConnected ? ui.held() : ui.muted());
    }

    private void paintSignal(Signal signal, int iconRes, String label, String description, int iconColor, int textColor) {
        if (signal.iconRes != iconRes) { signal.iconRes = iconRes; signal.icon.setImageResource(iconRes); }
        if (signal.iconColor != iconColor) { signal.iconColor = iconColor; signal.icon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN); }
        signal.label.setText(label);
        signal.label.setTextColor(textColor);
        signal.row.setContentDescription(description);
    }

    private static final class Signal {
        final LinearLayout row;
        final ImageView icon;
        final TextView label;
        int iconRes;
        int iconColor;
        Signal(LinearLayout row, ImageView icon, TextView label) { this.row = row; this.icon = icon; this.label = label; }
    }

    private void paintClipboard() {
        Json state = SharedClipboard.health(this);
        boolean shared = state.get("shared").boolValue();
        boolean ready = state.get("ready").boolValue() && state.get("automatic").boolValue();
        String title = state.get("status").string();
        String action = state.get("action").string();
        String value = !StationFeatures.master(this) ? "已暂停" : !shared ? "已关闭" : ready ? "同步中"
                : "remotePermissions".equals(action) ? "远程未授权"
                : "clipboard".equals(action) ? "待 Mac 接入"
                : "none".equals(action) ? (title.contains("已跳过") ? "已跳过" : "已暂停") : "需检查";
        clipboardLink.value.setText(value);
        boolean needsAction = StationFeatures.master(this) && shared && ("permissions".equals(action) || "mcp".equals(action) || "remotePermissions".equals(action));
        clipboardLink.value.setTextColor(ready ? ui.held() : needsAction ? ui.waiting() : ui.muted());
        clipboardLink.value.setContentDescription(title);
        ui.paintOn(clipboardLink.mark, ready);
    }
    private void paintNotification() {
        Json state = PhoneNotifications.status(this);
        boolean on = state.get("enabled").boolValue();
        boolean granted = state.get("accessGranted").boolValue();
        int count = (int) state.get("selectedCount").longValue();
        boolean listener = state.get("listenerConnected").boolValue();
        boolean mac = state.get("macOnline").boolValue();
        boolean mcpRunning = FileMcpService.listening() || PhoneRelayClient.connected();
        boolean remoteBlocked = HostProbe.remoteOnly(this) && !RemoteStore.permission(this, "personal");
        boolean ready = on && granted && count > 0 && listener && mac && mcpRunning && !remoteBlocked;
        boolean alerts = StationFeatures.selected(this, "alerts");
        boolean alertsReady = "已开启".equals(FeatureReadiness.reason(this, "alerts"));
        Json overview = HealthStatus.notificationOverview(StationFeatures.master(this), on, ready,
                !granted || count == 0 || !mcpRunning || !listener || remoteBlocked, alerts, alertsReady);
        String value = overview.get("label").string();
        String description = !on ? "手机通知同步已关闭；可设置电脑提醒"
                : !granted ? "手机通知同步未授予通知使用权"
                : count == 0 ? "手机通知同步尚未选择应用"
                : !mcpRunning ? "手机通知同步需要启用 MCP 服务"
                : !listener ? "等待系统连接通知服务"
                : remoteBlocked ? "远程通知接收未授权"
                : !mac ? "手机通知等待 Mac 接收"
                : "Mac 正在接收手机通知，已选 " + count + " 个应用";
        description = !StationFeatures.master(this) ? "手机工位已暂停，两个方向的选择已保留"
                : description + "；电脑提醒" + (!alerts ? "已关闭" : alertsReady ? "已开启" : "需要处理");
        notificationLink.value.setText(value);
        ready = overview.get("ready").boolValue();
        boolean needsAction = overview.get("attention").boolValue();
        notificationLink.value.setTextColor(ready ? ui.held() : needsAction ? ui.waiting() : ui.muted());
        notificationLink.value.setContentDescription(description);
        ui.paintOn(notificationLink.mark, ready);
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
    private void paintDisc(boolean linked) {
        if (linked) {
            hero.fill.setColor(ui.held());
            hero.icon.setColorFilter(ui.night ? getColor(R.color.page) : Color.WHITE, PorterDuff.Mode.SRC_IN);
            return;
        }
        hero.fill.setColor(getColor(R.color.well));
        hero.icon.setColorFilter(ui.ink(), PorterDuff.Mode.SRC_IN);
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
