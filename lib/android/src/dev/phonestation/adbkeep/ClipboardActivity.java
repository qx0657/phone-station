package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClipboardActivity extends StationActivity {
    private StationChrome ui;
    private android.widget.Switch shared;
    private StationChrome.Disclosure help;
    private StationChrome.Control images;
    private TextView status;
    private TextView message;
    private TextView session;
    private Button resolve;
    private Button resume;
    private String resolution = "permissions";
    private boolean painting;
    private boolean active;
    private boolean loading;
    private boolean saving;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable refresh = new Runnable() {
        public void run() { if (active) { load(); handler.postDelayed(this, 1_000); } }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new StationChrome(this);
        ui.back("共享剪贴板");
        LinearLayout settings = ui.card();
        shared = ui.groupSwitch(settings, "自动同步", "共享剪贴板");
        shared.setOnCheckedChangeListener((button, on) -> {
            if (!painting) { configure(Json.obj().put("shared", on)); }
        });
        ui.paragraph(settings, "复制文字或链接，自动同步到另一端。");
        images = ui.switchRow(settings, R.drawable.ic_status_clipboard, "同步 Mac 图片到相册");
        images.toggle.setOnCheckedChangeListener((button, on) -> {
            if (!painting) { configure(Json.obj().put("images", on)); }
        });
        ui.paragraph(settings, "开启后，新复制的 Mac 图片保存到手机相册。");
        LinearLayout sync = ui.card();
        ui.groupTitle(sync, "同步状态");
        status = ui.paragraph(sync, "正在核对同步状态…");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        resolve = ui.action(sync, "查看处理方法", false, view -> {
            if ("master".equals(resolution)) { startActivity(new Intent(this, MainActivity.class)); return; }
            if ("remotePermissions".equals(resolution)) {
                startActivity(RemotePermissionsActivity.intent(this, "personal")); return;
            }
            if ("mcp".equals(resolution)) {
                startActivity(new Intent(this, McpHelpActivity.class));
            } else {
                startActivity(new Intent(this, PermissionActivity.class).putExtra("feature", "clipboard"));
            }
        });
        resolve.setVisibility(View.GONE);
        resume = ui.action(sync, "恢复自动同步", true,
                view -> configure(Json.obj().put("automatic", true)));
        resume.setVisibility(View.GONE);
        message = ui.paragraph(sync, "");
        message.setVisibility(View.GONE);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        help = ui.disclosure("使用说明与权限", saved != null && saved.getBoolean("help_expanded"));
        LinearLayout details = help.body;
        session = ui.valueRow(details, "Mac 连接");
        session.setText(StationText.translate("正在核对"));
        ui.paragraph(details, "在任意应用复制，再到另一端粘贴。首次连接后，请重新复制一次。");
        ui.paragraph(details, "Mac 手机工位需保持运行并连接 MCP 服务；手机需启动并授权 Shizuku。本地连接和远程通道都可同步，无需打开投屏。");
        ui.linkRow(details, R.drawable.ic_status_permission, "查看 Shizuku 权限",
                view -> startActivity(new Intent(this, PermissionActivity.class).putExtra("feature", "clipboard")));
        ui.paragraph(details, "图片同步默认关闭；开启后每张最多 4 MB / 3200 万像素，保存成功显示无声通知，点通知查看图片。图片开关关闭时安静跳过，文字同步继续；文件与敏感内容仍跳过，锁屏时暂停。图片保存到相册，不覆盖手机剪贴板。文字仅保留在内存中，不显示正文、不保存历史；关闭共享后停止读取。");
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("help_expanded", help.expanded); super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume(); active = true; handler.post(refresh); }
    @Override protected void onPause() { active = false; handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    private void load() {
        if (loading || saving || !active) { return; }
        loading = true;
        worker.execute(() -> {
            // Display session metadata already held in memory; opening this page never reads clipboard text.
            Json state = SharedClipboard.status(this);
            handler.post(() -> {
                loading = false;
                if (!active || isFinishing() || saving) { return; }
                boolean enabled = state.get("shared").boolValue();
                boolean automatic = state.get("automatic").boolValue();
                painting = true;
                shared.setChecked(enabled);
                boolean imageEnabled = state.get("images").boolValue();
                images.toggle.setChecked(imageEnabled);
                painting = false;
                ui.paintOn(images.mark, imageEnabled);
                Json availability = state.get("availability");
                String reason = availability.get("reason").string();
                resolution = availability.get("action").string();
                String title = availability.get("status").string();
                status.setText(StationText.translate(title + (reason.isEmpty() ? "" : "\n" + reason)));
                boolean needsAction = "permissions".equals(resolution) || "mcp".equals(resolution) || "remotePermissions".equals(resolution) || "master".equals(resolution);
                status.setTextColor(enabled && needsAction ? ui.waiting()
                        : enabled && automatic && availability.get("ready").boolValue() ? ui.held() : ui.muted());
                session.setText(StationText.translate(!enabled ? "已停止" : state.get("macOnline").boolValue() ? "已连接" : "等待连接"));
                session.setTextColor(enabled && state.get("macOnline").boolValue() ? ui.held() : ui.muted());
                resolve.setVisibility(needsAction ? View.VISIBLE : View.GONE);
                resolve.setText(StationText.translate("master".equals(resolution) ? "前往首页恢复使用" : "remotePermissions".equals(resolution) ? "去授权剪贴板与通知"
                        : "mcp".equals(resolution) ? "查看 MCP 服务" : "查看 Shizuku 权限"));
                resume.setVisibility(enabled && !automatic ? View.VISIBLE : View.GONE);
            });
        });
    }
    private void configure(Json args) {
        if (saving) { return; }
        saving = true;
        shared.setEnabled(false);
        images.setEnabled(false);
        resume.setEnabled(false);
        message.setVisibility(View.GONE);
        worker.execute(() -> {
            String failure = null;
            try {
                SharedClipboard.configure(this, args);
                StationFeatures.changed(this, "clipboard");
                if (SharedClipboard.shared(this)) {
                    KeeperStore.setMcpEnabled(this, true);
                    FileMcpService.start(this);
                }
            } catch (RuntimeException error) { failure = error.getMessage(); }
            final String error = failure;
            handler.post(() -> {
                saving = false;
                shared.setEnabled(true);
                images.setEnabled(true);
                resume.setEnabled(true);
                if (!active) { return; }
                if (error != null) { message.setText(StationText.translate(error)); message.setTextColor(getColor(R.color.error)); message.setVisibility(View.VISIBLE); }
                else if (!StationFeatures.master(this)) { message.setText(StationText.translate("已保存，恢复使用后生效。")); message.setTextColor(ui.muted()); message.setVisibility(View.VISIBLE); }
                load();
                if (error == null && args.get("shared") != null && args.get("shared").boolValue()) { FeatureReadiness.prompt(this, "clipboard"); }
            });
        });
    }
}
