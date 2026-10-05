package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** Phone-owned authorization. Navigation only focuses a row and never grants access. */
public final class RemotePermissionsActivity extends Activity {
    static final String EXTRA_SCOPE = "dev.phonestation.adbkeep.REMOTE_SCOPE";
    private StationChrome ui;
    private TextView summary;
    private TextView channel;
    private TextView feedback;
    private boolean painting;
    private final Map<String, StationChrome.Control> controls = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() { paint(); handler.postDelayed(this, 1_000L); }
    };

    static Intent intent(android.content.Context context, String scope) {
        return new Intent(context, RemotePermissionsActivity.class).putExtra(EXTRA_SCOPE, scope);
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ui = new StationChrome(this);
        ui.back("远程访问范围");
        LinearLayout overview = ui.card();
        summary = ui.paragraph(overview, "");
        channel = ui.paragraph(overview, "");
        ui.paragraph(overview, "默认只允许查看状态。按需授权；未开启的能力保持关闭，不影响本地 adb 的原有权限。");
        ui.linkRow(overview, R.drawable.ic_status_wireless, "远程通道与配置",
                view -> startActivity(new Intent(this, RemoteRelayActivity.class)));
        LinearLayout permissions = ui.card();
        for (String scope : RemotePolicy.SCOPES) {
            if (!controls.isEmpty()) { ui.hairline(permissions, 16); }
            StationChrome.Control control = ui.switchRow(permissions, R.drawable.ic_status_permission, RemotePermissionInfo.title(scope));
            controls.put(scope, control);
            ui.paragraph(permissions, RemotePermissionInfo.detail(scope));
            control.toggle.setOnCheckedChangeListener((button, allowed) -> {
                if (painting) { return; }
                boolean savedPermission = RemoteStore.setPermission(this, scope, allowed);
                if (savedPermission && (KeeperStore.mcpEnabled(this) || RemoteStore.enabled(this))) { FileMcpService.start(this); }
                feedback.setText(!savedPermission ? "权限未保存，请重试。" : RemotePermissionInfo.title(scope)
                        + (allowed ? "已允许。" : "已关闭；已经执行的操作无法撤回。"));
                feedback.setTextColor(savedPermission ? ui.held() : getColor(R.color.error));
                feedback.setVisibility(View.VISIBLE);
                paint();
            });
        }
        feedback = ui.paragraph(permissions, "");
        feedback.setVisibility(View.GONE);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout help = ui.card();
        ui.paragraph(help, "更换中继地址、证书指纹或手机令牌后需要重新授权。权限变化会结束当前远程投屏与终端，并阻止旧排队任务启动；已经执行的操作无法撤回。");
        paint();
        String scope = getIntent().getStringExtra(EXTRA_SCOPE);
        StationChrome.Control focus = controls.get(scope);
        if (focus != null) {
            ui.scroll.post(() -> {
                android.graphics.Rect bounds = new android.graphics.Rect();
                focus.row.getDrawingRect(bounds);
                ui.column.offsetDescendantRectToMyCoords(focus.row, bounds);
                ui.scroll.smoothScrollTo(0, Math.max(0, bounds.top - ui.dp(72)));
                focus.toggle.requestFocus();
                focus.row.announceForAccessibility(RemotePermissionInfo.title(scope));
            });
        }
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private void paint() {
        boolean configured = RemoteStore.configured(this);
        summary.setText(RemoteStore.permissionSummary(this));
        channel.setText(!configured ? "尚未配置远程通道。可预先选择访问范围；更换中继资料时需要重新确认。"
                : !RemoteStore.enabled(this) ? "远程通道已关闭，授权选择已保留，开启通道后生效。"
                : "远程通道 · " + PhoneRelayClient.connectionLabel(this));
        painting = true;
        for (Map.Entry<String, StationChrome.Control> entry : controls.entrySet()) {
            boolean on = RemoteStore.permission(this, entry.getKey());
            entry.getValue().toggle.setChecked(on);
            entry.getValue().setEnabled(true);
            ui.paintOn(entry.getValue().mark, on);
        }
        painting = false;
    }
}
