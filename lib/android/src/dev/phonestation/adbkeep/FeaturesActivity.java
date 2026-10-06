package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** Every child switch displays the saved choice, including when permission is missing. */
public final class FeaturesActivity extends StationActivity {
    static final String EXTRA_GROUP = "feature_group";
    private StationChrome ui;
    private TextView summary;
    private String feedbackKey = "";
    private String feedbackMessage = "";
    private boolean feedbackError;
    private boolean painting;
    private boolean saving;
    private final Map<String, StationChrome.Control> controls = new LinkedHashMap<>();
    private final Map<String, TextView> states = new LinkedHashMap<>();
    private final Map<String, View> permissionLinks = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() { public void run() { paint(); handler.postDelayed(this, 2_000); } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String group = getIntent().getStringExtra(EXTRA_GROUP);
        boolean focused = group != null && FeaturePolicy.groupKeys(group).length > 0;
        ui = new StationChrome(this); ui.back(focused ? FeaturePolicy.groupTitle(group) : "功能管理");
        LinearLayout overview = ui.card(); summary = ui.paragraph(overview, "");
        ui.paragraph(overview, "按需开启功能。在首页暂停或恢复使用，所有选择都会保留。");
        if (!focused) {
            LinearLayout collaboration = ui.card(); ui.groupTitle(collaboration, "通知与剪贴板");
            add(collaboration, "clipboard"); add(collaboration, "notifications"); add(collaboration, "alerts");
            ui.linkRow(collaboration, R.drawable.ic_status_notify, "通知应用、显示方式与铃声", v -> startActivity(new Intent(this, NotificationActivity.class)));
            ui.linkRow(collaboration, R.drawable.ic_status_clipboard, "剪贴板同步设置", v -> startActivity(new Intent(this, ClipboardActivity.class)));
        }
        for (String category : focused ? new String[] {group} : FeaturePolicy.MCP_GROUPS) {
            LinearLayout card = ui.card();
            ui.groupTitle(card, FeaturePolicy.groupTitle(category));
            for (String key : FeaturePolicy.groupKeys(category)) { add(card, key); }
            if ("files".equals(category)) { ui.paragraph(card, "文件修改包含永久删除，删除的文件不进回收站。"); }
            if ("commands".equals(category)) {
                ui.paragraph(card, "使用已启动并授权的 Shizuku 执行命令和安装应用。远程安装还需允许文件读取和修改。");
            }
        }
        LinearLayout access = ui.card();
        ui.linkRow(access, R.drawable.ic_status_permission, "远程访问范围", v -> startActivity(RemotePermissionsActivity.intent(this, "")));
        ui.paragraph(access, "功能开关对本地 MCP 和远程通道都生效。远程访问范围决定可信远程电脑可以调用哪些已开启功能；系统权限只决定功能是否就绪。");
        paint();
    }
    private void add(LinearLayout parent, String key) {
        StationChrome.Control control = ui.switchRow(parent, R.drawable.ic_status_permission, FeaturePolicy.title(key));
        controls.put(key, control); states.put(key, ui.paragraph(parent, ""));
        states.get(key).setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        permissionLinks.put(key, ui.statusLinkRow(parent, R.drawable.ic_status_permission, "处理权限与访问范围",
                v -> startActivity(new Intent(this, PermissionActivity.class).putExtra("feature", key))).row);
        control.toggle.setOnCheckedChangeListener((button, on) -> {
            if (painting || saving) { return; }
            saving = true;
            for (StationChrome.Control item : controls.values()) { item.setEnabled(false); }
            feedbackKey = key; feedbackMessage = "正在保存…"; feedbackError = false; paint();
            new Thread(() -> {
                String failure = null;
                try { if (!StationFeatures.set(this, key, on)) { throw new FileFailure("设置未保存，请重试"); } }
                catch (RuntimeException error) { failure = error.getMessage(); }
                final String result = failure;
                handler.post(() -> {
                    saving = false;
                    if (isDestroyed()) { return; }
                    feedbackMessage = result != null ? result : !StationFeatures.master(this) ? "已保存，恢复使用后生效。" : "";
                    feedbackError = result != null;
                    paint(); if (result == null && on && hasWindowFocus()) { FeatureReadiness.prompt(this, key); }
                });
            }, "station-feature").start();
        });
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private void paint() {
        summary.setText(StationText.translate(StationFeatures.master(this) ? "手机工位已开启 · 按需选择功能" : "手机工位已暂停 · 子开关选择已保留"));
        painting = true;
        for (String key : controls.keySet()) {
            boolean on = StationFeatures.selected(this, key);
            if (!saving || !key.equals(feedbackKey)) { controls.get(key).toggle.setChecked(on); }
            ui.paintOn(controls.get(key).mark, on);
            controls.get(key).setEnabled(!saving);
            String reason = FeatureReadiness.reason(this, key);
            boolean needsAction = on && StationFeatures.master(this) && !"已开启".equals(reason);
            boolean feedbackVisible = key.equals(feedbackKey) && !feedbackMessage.isEmpty();
            states.get(key).setText(StationText.translate(feedbackVisible ? feedbackMessage : reason.replaceFirst("^已开启 · ", "")));
            states.get(key).setVisibility(needsAction || feedbackVisible ? View.VISIBLE : View.GONE);
            permissionLinks.get(key).setVisibility(needsAction ? View.VISIBLE : View.GONE);
            states.get(key).setTextColor(feedbackVisible ? feedbackError ? getColor(R.color.error) : ui.muted() : ui.waiting());
        }
        painting = false;
    }
}
