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
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private StationChrome ui;
    private StationChrome.Badge hero;
    private TextView headline;
    private View connectionBlock;
    private StationChrome.Control keep;
    private StationChrome.Notice notice;
    private StationChrome.Fact wirelessFact;
    private StationChrome.Fact usbFact;
    private StationChrome.Fact wifiFact;
    private StationChrome.Control mcp;
    private TextView mcpAddress;
    private TextView notificationValue;
    private TextView permissionValue;
    private String shownConnection;
    private int connectionFade;
    private boolean updatingSwitch;
    private boolean updatingMcp;
    private Boolean mcpHeld;
    private long mcpHeldAt;
    private boolean active;

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
        ui = new StationChrome(this);

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
        headline.setMaxLines(1);
        if (Build.VERSION.SDK_INT >= 28) {
            headline.setAccessibilityHeading(true);
        }
        LinearLayout.LayoutParams headlineParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headlineParams.setMarginStart(dp(14));
        header.addView(headline, headlineParams);
        link.addView(header, matchWrap());

        wirelessFact = ui.fact(link, R.drawable.ic_status_wireless, "无线调试");
        ui.hairline(link, 52);
        usbFact = ui.fact(link, R.drawable.ic_status_usb, "USB 调试");
        ui.hairline(link, 52);
        wifiFact = ui.fact(link, R.drawable.ic_status_wifi, "Wi-Fi");
        notice = ui.notice(link);

        LinearLayout services = ui.card();
        ui.groupTitle(services, "服务");
        keep = ui.switchRow(services, R.drawable.ic_status_keep, "保持无线调试");
        keep.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updatingSwitch) {
                return;
            }
            KeeperStore.setEnabled(this, checked);
            if (checked) {
                KeeperService.start(this);
            } else {
                stopService(new Intent(this, KeeperService.class));
                KeeperAlarm.cancel(this);
            }
            render();
        });
        ui.hairline(services, 62);
        mcp = ui.switchRow(services, R.drawable.ic_status_mcp, "MCP服务");
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
                FileMcpService.stop(this);
            }
            handler.postDelayed(() -> {
                if (active) {
                    render();
                }
            }, 400L);
        });
        mcpAddress = ui.address(services);

        LinearLayout settings = ui.card();
        ui.groupTitle(settings, "设置");
        notificationValue = ui.linkRow(
                settings, R.drawable.ic_status_notify, "通知",
                view -> startActivity(new Intent(this, NotificationActivity.class)));
        ui.hairline(settings, 62);
        permissionValue = ui.linkRow(
                settings, R.drawable.ic_status_permission, "权限",
                view -> startActivity(new Intent(this, PermissionActivity.class)));
        ui.hairline(settings, 62);
        ui.linkRow(
                settings, R.drawable.ic_status_mcp, "MCP说明",
                view -> startActivity(new Intent(this, McpHelpActivity.class)));
        ui.hairline(settings, 62);
        TextView aboutValue = ui.linkRow(
                settings, R.drawable.ic_status_about, "关于",
                view -> startActivity(new Intent(this, AboutActivity.class)));
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
        if (KeeperStore.mcpEnabled(this)) {
            FileMcpService.start(this);
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        AlertChannel.ensure(this);
        if (KeeperStore.isEnabled(this)) {
            KeeperService.start(this);
        }
        if (KeeperStore.mcpEnabled(this)) {
            FileMcpService.start(this);
        }
        StationNotifications.restore(this);
        render();
        handler.postDelayed(refresh, 2_000L);
    }

    @Override
    protected void onPause() {
        active = false;
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void render() {
        HostProbe host = HostProbe.current(this);
        paintConnection(host);
        KeeperCopy copy = KeeperEngine.current(this);
        updatingSwitch = true;
        keep.toggle.setChecked(KeeperStore.isEnabled(this));
        updatingSwitch = false;
        ui.paintOn(keep.mark, keep.toggle.isChecked());
        paintNotice(copy);
        paintFact(wirelessFact, copy.wireless, copy, KeeperCopy.Emphasis.WIRELESS);
        paintFact(usbFact, copy.usb, copy, KeeperCopy.Emphasis.USB);
        paintFact(wifiFact, copy.wifi, copy, KeeperCopy.Emphasis.WIFI);
        paintMcp();
        paintNotification();
        paintPermissions();
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
    }

    private void paintConnection(HostProbe host) {
        int color = host.linked ? ui.held() : ui.ink();
        if (host.headline.equals(shownConnection)) {
            headline.setTextColor(color);
            paintDisc(host.linked);
            return;
        }
        boolean first = shownConnection == null;
        shownConnection = host.headline;
        Runnable apply = () -> {
            headline.setText(host.headline);
            headline.setTextColor(color);
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
        String line = copy.notice();
        if (line.isEmpty()) {
            notice.view.setVisibility(View.GONE);
            return;
        }
        notice.view.setVisibility(View.VISIBLE);
        notice.view.setText(line);
        int tone = toneColor(copy);
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
        if (live) {
            mcpAddress.setText(FileMcpService.listenUrl());
            mcpAddress.setVisibility(View.VISIBLE);
        } else {
            mcpAddress.setVisibility(View.GONE);
        }
    }

    private void paintFact(
            StationChrome.Fact fact, String text, KeeperCopy copy, KeeperCopy.Emphasis which) {
        fact.value.setText(text);
        boolean positive = "开".equals(text) || "已连接".equals(text);
        int color;
        if (copy.emphasis == which) {
            color = toneColor(copy);
        } else if (positive) {
            color = ui.held();
        } else {
            color = ui.muted();
        }
        fact.value.setTextColor(color);
        fact.value.setTypeface(copy.emphasis == which || positive
                ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.DEFAULT);
        fact.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
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
