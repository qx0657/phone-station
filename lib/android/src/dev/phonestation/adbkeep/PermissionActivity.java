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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/** 能读到的权限，以及还没开时怎么去开。 */
public final class PermissionActivity extends Activity {
    private static final int REQUEST_NOTIFY = 2;
    private static final int REQUEST_SHIZUKU = 3;
    private static final String[][] HONOR_STARTUP = {
            // PGT-AN20 Android 15：普通入口可用，AppControl 入口要求系统签名权限。
            {
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            },
            {
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"
            }
    };

    private StationChrome ui;
    private LinearLayout pending;
    private LinearLayout allowed;
    private LinearLayout allowedRows;
    private LinearLayout manual;
    private StationChrome.Link allowedLink;
    private StationChrome.Link remotePermissions;
    private boolean expanded;
    private boolean active;
    private String shown;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!active) { return; }
            render();
            handler.postDelayed(this, 1_000L);
        }
    };
    private final Shizuku.OnBinderReceivedListener shizukuBinder = this::render;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermission =
            (code, grantResult) -> render();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new StationChrome(this);
        ui.back("权限与检查");
        String feature = getIntent().getStringExtra("feature");
        if (feature != null && !feature.isEmpty()) {
            LinearLayout context = ui.card();
            ui.groupTitle(context, FeaturePolicy.title(feature));
            ui.paragraph(context, "只检查这项功能需要的设置。完成后返回，检查结果会自动更新。");
        }
        expanded = savedInstanceState != null && savedInstanceState.getBoolean("allowed_expanded");
        pending = ui.card();
        LinearLayout remoteAccess = ui.card();
        remotePermissions = ui.statusLinkRow(remoteAccess, R.drawable.ic_status_permission, "远程访问范围",
                view -> startActivity(RemotePermissionsActivity.intent(this, "")));
        ui.paragraph(remoteAccess, "选择远程电脑可以使用的能力。默认关闭，按需允许。");
        remoteAccess.setVisibility(feature == null || feature.isEmpty() ? View.VISIBLE : View.GONE);
        allowed = ui.card();
        allowedLink = ui.statusLinkRow(allowed, R.drawable.ic_status_permission, "已满足的权限", view -> {
            expanded = !expanded;
            paintExpanded();
        });
        allowedRows = new LinearLayout(this);
        allowedRows.setOrientation(LinearLayout.VERTICAL);
        allowed.addView(allowedRows, matchWrap());
        manual = ui.card();
        Shizuku.addBinderReceivedListenerSticky(shizukuBinder);
        Shizuku.addRequestPermissionResultListener(shizukuPermission);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        Shizuku.removeBinderReceivedListener(shizukuBinder);
        Shizuku.removeRequestPermissionResultListener(shizukuPermission);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlertChannel.ensure(this);
        active = true;
        handler.post(refresh);
    }

    @Override protected void onPause() {
        active = false;
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("allowed_expanded", expanded);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFY) {
            render();
        }
    }

    private void render() {
        String feature = getIntent().getStringExtra("feature");
        PermissionCopy.Board board = FeatureReadiness.board(this, feature);
        Json issues = FeatureReadiness.checks(this, feature);
        Json extra = Json.arr();
        boolean shizukuPending = false;
        StringBuilder key = new StringBuilder(issues.emit());
        String remoteSummary = RemoteStore.permissionSummary(this);
        remotePermissions.value.setText(remoteSummary);
        remotePermissions.value.setTextColor(ui.muted());
        key.append(remoteSummary);
        for (PermissionCopy.Row row : board.rows) {
            key.append(row.title).append(row.value).append(row.tone);
            if ("Shizuku".equals(row.title) && row.tone == PermissionCopy.Tone.WAITING) {
                shizukuPending = true;
            }
        }
        if (key.toString().equals(shown)) { return; }
        shown = key.toString();
        for (Json issue : issues.array()) {
            String id = issue.get("id").string();
            if (id.startsWith("permission-") || ("shizuku".equals(id) && shizukuPending)) { continue; }
            extra.add(issue);
        }
        int count = board.missing + extra.array().size();
        pending.removeAllViews();
        allowedRows.removeAllViews();
        manual.removeAllViews();
        ui.groupTitle(pending, count == 0 ? "当前无需处理" : "还需完成 " + count + " 项设置");
        ui.paragraph(pending, count == 0
                ? StationFeatures.master(this) ? "当前检查的权限和必要设置已满足。实际连接与同步状态请查看对应功能页。" : "手机工位已暂停；开启功能后检查所需权限。"
                : "先处理下面这些项目；返回后会自动更新检查结果。");
        for (PermissionCopy.Row row : board.rows) {
            if (row.tone == PermissionCopy.Tone.WAITING) {
                ui.hairline(pending, 16);
                addRow(pending, row);
            } else if (row.tone == PermissionCopy.Tone.HELD) {
                ui.hairline(allowedRows, 16);
                addRow(allowedRows, row);
            } else {
                ui.groupTitle(manual, "后台运行");
                ui.paragraph(manual, "自启动状态需到系统中手动确认。");
                addRow(manual, row);
            }
        }
        for (Json issue : extra.array()) {
            ui.hairline(pending, 16);
            ui.issueRow(pending, issue.get("title").string(), issue.get("detail").string(),
                    view -> openIssue(issue));
        }
        int granted = (int) java.util.Arrays.stream(board.rows).filter(row -> row.tone == PermissionCopy.Tone.HELD).count();
        allowed.setVisibility(granted == 0 ? View.GONE : View.VISIBLE);
        allowedLink.value.setText(granted + " 项");
        allowedLink.value.setTextColor(ui.muted());
        ui.paintOn(allowedLink.mark, true);
        manual.setVisibility(manual.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        paintExpanded();
    }

    private void paintExpanded() {
        allowedRows.setVisibility(expanded ? View.VISIBLE : View.GONE);
        allowedLink.chevron.setRotation(expanded ? 270 : 90);
        String label = "已满足的权限，" + allowedLink.value.getText() + "，" + (expanded ? "收起" : "展开");
        allowedLink.row.setContentDescription(label);
        allowedLink.row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (Build.VERSION.SDK_INT >= 30) {
            allowedLink.row.setStateDescription(expanded ? "已展开" : "已折叠");
        }
    }

    private void openIssue(Json issue) {
        String id = issue.get("id").string();
        if ("shizuku".equals(id)) { openShizuku(); return; }
        if ("notification-apps".equals(id)) { startActivity(new Intent(this, NotificationAppsActivity.class)); return; }
        if ("notification-access".equals(id) || "notification-listener".equals(id)) {
            start(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); return;
        }
        String destination = issue.get("destination").string();
        if ("mcp".equals(destination)) {
            startActivity(new Intent(this, McpHelpActivity.class));
        } else if ("notifications".equals(destination)) {
            startActivity(new Intent(this, NotificationActivity.class));
        } else if ("remoteRelay".equals(destination)) {
            startActivity(new Intent(this, RemoteRelayActivity.class));
        } else if ("remotePermissions".equals(destination)) {
            startActivity(RemotePermissionsActivity.intent(this, issue.get("scope") == null ? "personal" : issue.get("scope").string()));
        } else {
            startActivity(new Intent(this, ClipboardActivity.class));
        }
    }

    private void addRow(LinearLayout parent, PermissionCopy.Row row) {
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

        String detail = row.tone == PermissionCopy.Tone.WAITING ? PermissionCopy.impact(row) + row.hint : row.hint;
        if (!detail.isEmpty()) {
            TextView hint = ui.text(13);
            hint.setText(detail);
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
        parent.addView(block, matchWrap());
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
        if ("HONOR".equalsIgnoreCase(Build.BRAND) || "HONOR".equalsIgnoreCase(Build.MANUFACTURER)) {
            for (String[] target : HONOR_STARTUP) {
                Intent intent = new Intent();
                intent.setClassName(target[0], target[1]);
                if (start(intent)) {
                    return;
                }
            }
        }
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        details.setData(Uri.parse("package:" + getPackageName()));
        if (start(details) || start(new Intent(Settings.ACTION_APPLICATION_SETTINGS))) {
            return;
        }
        Log.w(KeeperEngine.TAG, "startup settings unavailable");
        Toast.makeText(this, "无法打开，请在系统设置中查找应用的后台运行或自启动设置。", Toast.LENGTH_LONG).show();
    }

    private boolean start(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
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
