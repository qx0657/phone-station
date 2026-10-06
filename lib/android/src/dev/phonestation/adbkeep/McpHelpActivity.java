package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** Phone-side service, capability readiness and setup; credentials are never displayed. */
public final class McpHelpActivity extends StationActivity {
    private StationChrome ui;
    private TextView status;
    private TextView summary;
    private TextView feedback;
    private TextView address;
    private TextView addressHint;
    private View resume;
    private Button retry;
    private StationChrome.Control local;
    private StationChrome.Link remote;
    private StationChrome.Disclosure help;
    private final Map<String, StationChrome.Link> capabilities = new LinkedHashMap<>();
    private boolean painting;
    private boolean saving;
    private boolean active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable connectionChanged = () -> { if (active) { paint(); } };
    private final Runnable refresh = new Runnable() {
        public void run() { if (active) { paint(); handler.postDelayed(this, 2_000L); } }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new StationChrome(this);
        ui.back("MCP 服务");
        LinearLayout overview = ui.card();
        status = ui.valueRow(overview, "服务状态");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        summary = ui.paragraph(overview, "");
        resume = ui.statusLinkRow(overview, R.drawable.ic_play, "前往首页恢复使用", view -> startActivity(
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP))).row;

        LinearLayout features = ui.card();
        ui.groupTitle(features, "可用功能");
        for (String group : FeaturePolicy.MCP_GROUPS) {
            if (!capabilities.isEmpty()) { ui.hairline(features, 62); }
            int icon = "files".equals(group) ? R.drawable.ic_status_files
                    : "commands".equals(group) ? R.drawable.ic_status_terminal : R.drawable.ic_status_computer;
            String detail = "files".equals(group) ? "读取、修改与打开文件"
                    : "commands".equals(group) ? "Shell 命令与 APK 安装" : "投屏、录屏、截屏、亮屏与手电筒";
            capabilities.put(group, ui.statusLinkRow(features, icon, FeaturePolicy.groupTitle(group), detail,
                    view -> startActivity(new Intent(this, FeaturesActivity.class).putExtra(FeaturesActivity.EXTRA_GROUP, group))));
        }
        ui.paragraph(features, "在电脑或 AI 客户端使用；点进各项管理功能与权限。");

        LinearLayout access = ui.card();
        ui.groupTitle(access, "接入方式");
        local = ui.switchRow(access, R.drawable.ic_status_mcp, "本地 MCP 接入");
        local.toggle.setOnCheckedChangeListener((button, on) -> { if (!painting && !saving) { saveLocal(on); } });
        ui.paragraph(access, "供已通过无线调试或 USB 连接的电脑使用，关闭不影响远程接入。");
        feedback = ui.paragraph(access, "");
        feedback.setVisibility(View.GONE);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        retry = ui.action(access, "重新启动本地接入", false, view -> saveLocal(true));
        ui.hairline(access, 62);
        remote = ui.statusLinkRow(access, R.drawable.ic_status_cloud, "远程连接",
                view -> startActivity(new Intent(this, RemoteRelayActivity.class)));
        ui.hairline(access, 62);
        ui.statusLinkRow(access, R.drawable.ic_status_wireless, "本地连接设置",
                view -> startActivity(new Intent(this, ConnectionActivity.class)));

        help = ui.disclosure("接入信息与说明", saved != null && saved.getBoolean("help_expanded"));
        ui.groupTitle(help.body, "手机本机地址");
        address = ui.address(help.body);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) address.getLayoutParams();
        params.setMarginStart(ui.dp(16)); address.setLayoutParams(params);
        addressHint = ui.paragraph(help.body, "");
        for (McpHelp.Section section : McpHelp.sections()) {
            ui.hairline(help.body, 16);
            addSection(ui, help.body, section);
        }
        paint();
    }

    @Override protected void onResume() {
        super.onResume(); active = true; StationConnectionEvents.add(connectionChanged); handler.post(refresh);
    }
    @Override protected void onPause() {
        active = false; StationConnectionEvents.remove(connectionChanged); handler.removeCallbacks(refresh); super.onPause();
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putBoolean("help_expanded", help.expanded); super.onSaveInstanceState(saved);
    }

    private void saveLocal(boolean on) {
        if (saving) { return; }
        saving = true;
        feedback.setText(StationText.translate("正在保存…")); feedback.setTextColor(ui.muted()); feedback.setVisibility(View.VISIBLE);
        paint();
        Context context = getApplicationContext();
        new Thread(() -> {
            String failure = "";
            try {
                if (!KeeperStore.setMcpEnabled(context, on)) { failure = "设置未保存，请重试。"; }
                else if (on || RemoteStore.enabled(context)) { FileMcpService.start(context); }
                else { FileMcpService.stop(context); }
            } catch (RuntimeException error) { failure = "未能完成切换，请检查下方开关与服务状态后重试。"; }
            final String result = failure;
            handler.post(() -> {
                saving = false;
                if (isDestroyed()) { return; }
                String message = !result.isEmpty() ? result : !StationFeatures.master(this)
                        ? "已保存，恢复手机工位后生效。" : "";
                feedback.setText(StationText.translate(message)); feedback.setTextColor(result.isEmpty() ? ui.muted() : getColor(R.color.error));
                feedback.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
                paint();
            });
        }, "station-mcp-setting").start();
    }

    private void paint() {
        boolean master = StationFeatures.master(this);
        Json service = FileMcpService.status(this);
        boolean available = service.get("available").boolValue();
        status.setText(StationText.translate(service.get("label").string()));
        status.setTextColor(service.get("attention").boolValue() ? ui.waiting() : available ? ui.held() : ui.muted());
        summary.setText(StationText.translate(service.get("detail").string()));
        resume.setVisibility(master ? View.GONE : View.VISIBLE);
        painting = true;
        if (!saving) { local.toggle.setChecked(KeeperStore.mcpEnabled(this)); }
        local.setEnabled(!saving); ui.paintOn(local.mark, local.toggle.isChecked());
        painting = false;
        retry.setVisibility(master && KeeperStore.mcpEnabled(this) && !service.get("localReady").boolValue() ? View.VISIBLE : View.GONE);
        retry.setEnabled(!saving);
        remote.value.setText(StationText.translate(service.get("remoteLabel").string()));
        remote.value.setTextColor(service.get("remoteReady").boolValue() ? ui.held() : ui.muted());
        ui.paintOn(remote.mark, service.get("remoteReady").boolValue());
        for (String group : capabilities.keySet()) {
            int selected = 0, blocked = 0;
            for (String key : FeaturePolicy.groupKeys(group)) {
                if (!StationFeatures.selected(this, key)) { continue; }
                selected++;
                if (master && !"已开启".equals(FeatureReadiness.reason(this, key))) { blocked++; }
            }
            StationChrome.Link link = capabilities.get(group);
            link.value.setText(StationText.translate(McpStatus.capabilities(master, available, selected, blocked)));
            boolean ready = master && available && selected > 0 && blocked == 0;
            link.value.setTextColor(master && blocked > 0 ? ui.waiting() : ready ? ui.held() : ui.muted());
            link.value.setContentDescription(StationText.translate(link.value.getText() + "，已开启 " + selected + " 项"
                    + (blocked > 0 ? "，其中 " + blocked + " 项需处理权限或访问范围" : "")));
            ui.paintOn(link.mark, ready);
        }
        boolean live = service.get("localReady").boolValue();
        address.setVisibility(live ? View.VISIBLE : View.GONE);
        if (live) { address.setText(StationText.translate(FileMcpService.listenUrl())); }
        addressHint.setText(StationText.translate(live
                ? "此地址只在手机本机使用。电脑端的地址和授权信息请从 Mac「MCP 服务」中获取。"
                : !master ? "恢复手机工位后显示本地接入地址。"
                : "本地接入运行后显示地址；远程接入不需要此地址。"));
    }

    private void addSection(StationChrome ui, LinearLayout card, McpHelp.Section section) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        int side = ui.dp(16);
        block.setPadding(side, ui.dp(12), side, ui.dp(12));

        TextView title = ui.text(16);
        title.setText(StationText.translate(section.title));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= 28) {
            title.setAccessibilityHeading(true);
        }
        block.addView(title, matchWrap());

        TextView body = ui.text(15);
        body.setText(StationText.translate(section.body));
        body.setIncludeFontPadding(false);
        body.setLineSpacing(0f, 1.3f);
        body.setTextIsSelectable(true);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = ui.dp(6);
        block.addView(body, bodyParams);
        card.addView(block, matchWrap());
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
