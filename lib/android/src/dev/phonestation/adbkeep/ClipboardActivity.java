package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClipboardActivity extends Activity {
    private StationChrome ui;
    private StationChrome.Control shared;
    private StationChrome.Control automatic;
    private TextView phone;
    private TextView mac;
    private TextView status;
    private TextView message;
    private Button copy;
    private String macText;
    private boolean painting;
    private boolean active;
    private boolean busy;
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
        shared = ui.switchRow(settings, R.drawable.ic_status_clipboard, "共享剪贴板");
        shared.toggle.setOnCheckedChangeListener((button, on) -> {
            if (!painting) {
                configure(Json.obj().put("shared", on));
            }
        });
        automatic = ui.switchRow(settings, R.drawable.ic_status_clipboard, "自动双向同步");
        automatic.toggle.setOnCheckedChangeListener((button, on) -> {
            if (!painting) { configure(Json.obj().put("automatic", on)); }
        });
        status = ui.paragraph(settings, "正在读取状态…");
        LinearLayout local = ui.card();
        ui.groupTitle(local, "手机剪贴板");
        phone = preview(local);
        LinearLayout desktop = ui.card();
        ui.groupTitle(desktop, "Mac 剪贴板");
        mac = preview(desktop);
        copy = ui.action(desktop, "复制到手机剪贴板", true, view -> {
            if (macText == null) { return; }
            ClipboardManager board = getSystemService(ClipboardManager.class);
            if (board != null) {
                board.setPrimaryClip(ClipData.newPlainText("手机工位", macText));
                message.setText("已复制到手机，可切换到其他应用粘贴");
                load();
            }
        });
        message = ui.paragraph(desktop, "");
        LinearLayout help = ui.card();
        ui.paragraph(help, "支持文字和链接。自动同步开启后，在任意应用复制文字即可同步。锁屏、图片、文件和标记为敏感的内容会跳过。两端内容只保留在内存中。");
        ui.linkRow(help, R.drawable.ic_status_permission, "查看 Shizuku 权限",
                view -> startActivity(new Intent(this, PermissionActivity.class)));
        ui.paragraph(help, "Mac 需运行手机工位并连接 MCP 服务；本地无线 adb 和远程中继都可用。关闭自动同步仍可预览和手动复制；关闭共享后停止读取。");
    }
    @Override protected void onResume() { super.onResume(); active = true; handler.post(refresh); }
    @Override protected void onPause() { active = false; handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    private TextView preview(LinearLayout parent) {
        TextView text = ui.text(16);
        text.setText("等待剪贴板…");
        text.setTextIsSelectable(true);
        text.setMaxLines(8);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16));
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }
    private void load() {
        if (busy || !active) { return; }
        busy = true;
        worker.execute(() -> {
            Json state = SharedClipboard.exchange(this, Json.obj());
            handler.post(() -> {
                busy = false;
                if (!active || isFinishing()) { return; }
                boolean enabled = state.get("shared").boolValue();
                painting = true;
                shared.toggle.setChecked(enabled);
                automatic.toggle.setChecked(state.get("automatic").boolValue());
                automatic.setEnabled(enabled);
                painting = false;
                ui.paintOn(shared.mark, enabled);
                ui.paintOn(automatic.mark, enabled && state.get("automatic").boolValue());
                Json error = state.get("error");
                boolean online = state.get("macOnline").boolValue();
                status.setText(!enabled ? "共享已关闭" : error != null ? error.string()
                        : online ? (state.get("automatic").boolValue() ? "Mac 已连接 · 自动同步中" : "Mac 已连接 · 手动共享")
                        : "等待 Mac 手机工位连接 MCP 服务");
                Json own = state.get("phone");
                Json peer = state.get("mac");
                phone.setText(!enabled ? "开启共享后显示" : error != null ? "请先启动并授权 Shizuku" : previewText(own));
                mac.setText(!enabled ? "开启共享后显示" : !online ? "Mac 未连接，连接后显示当前内容" : previewText(peer));
                macText = enabled && online && peer != null && !peer.isNull() && "text".equals(peer.get("kind").string())
                        ? peer.get("text").string() : null;
                copy.setEnabled(macText != null);
            });
        });
    }
    private void configure(Json args) {
        shared.setEnabled(false);
        automatic.setEnabled(false);
        worker.execute(() -> {
            String failure = null;
            try {
                SharedClipboard.configure(this, args);
                if (SharedClipboard.shared(this)) {
                    KeeperStore.setMcpEnabled(this, true);
                    FileMcpService.start(this);
                }
            } catch (RuntimeException error) { failure = error.getMessage(); }
            final String error = failure;
            handler.post(() -> {
                if (!active) { return; }
                shared.setEnabled(true);
                if (error != null) { message.setText(error); }
                load();
            });
        });
    }
    private String previewText(Json clip) {
        if (clip == null || clip.isNull()) { return "等待剪贴板…"; }
        switch (clip.get("kind").string()) {
            case "text": return clip.get("text").string().isEmpty() ? "空文字" : clip.get("text").string();
            case "sensitive": return "敏感内容已跳过";
            case "unsupported": return "当前是图片或文件，仅支持文字";
            case "oversize": return "文字超过 100000 字，已跳过";
            case "locked": return "手机已锁定，解锁后继续";
            default: return "剪贴板为空";
        }
    }
}
