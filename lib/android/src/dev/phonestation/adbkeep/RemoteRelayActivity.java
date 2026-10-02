package dev.phonestation.adbkeep;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Configure this phone independently of adb. Stored credentials are never displayed. */
public final class RemoteRelayActivity extends Activity {
    private StationChrome ui;
    private EditText endpoint;
    private EditText pin;
    private EditText token;
    private TextView status;
    private StationChrome.Control remote;
    private boolean updatingRemote;
    private TextView message;
    private Button save;
    private Button forget;
    private boolean busy;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() { paintStatus(); handler.postDelayed(this, 2_000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new StationChrome(this);
        ui.back("远程中继");
        LinearLayout overview = ui.card();
        remote = ui.switchRow(overview, R.drawable.ic_status_wireless, "启用远程通道");
        remote.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updatingRemote || busy) { return; }
            RemoteStore.setEnabled(this, checked);
            if (checked) { KeeperStore.setMcpEnabled(this, true); }
            if (KeeperStore.mcpEnabled(this) || RemoteStore.enabled(this)) { FileMcpService.start(this); }
            else { FileMcpService.stop(this); }
            paintStatus();
        });
        status = ui.valueRow(overview, "连接状态");
        ui.paragraph(overview, "让电脑通过互联网使用这台手机的 MCP 工具。手机主动连接中继，Wi-Fi 和移动网络都可使用。");
        LinearLayout form = ui.card();
        ui.groupTitle(form, "中继配置");
        endpoint = ui.field(form, "服务器地址", "https://relay.example.com", false);
        endpoint.setText(RemoteStore.endpoint(this));
        pin = ui.field(form, "证书指纹 · SPKI SHA-256", "64 位十六进制指纹", false);
        pin.setText(RemoteStore.pin(this));
        token = ui.field(form, "手机令牌", RemoteStore.configured(this) ? "留空保留已有令牌" : "64 位十六进制令牌", true);
        token.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        token.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) { return false; }
            save(); return true;
        });
        endpoint.setNextFocusForwardId(pin.getId());
        pin.setNextFocusForwardId(token.getId());
        ui.paragraph(form, "资料由中继管理员提供。这里填手机令牌；Mac 使用另一枚电脑令牌。更换地址或指纹时需重新填写令牌。");
        message = ui.paragraph(form, "");
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setVisibility(View.GONE);
        save = ui.action(form, "保存并连接", true, view -> save());
        forget = ui.action(form, "清除手机配置", false, view -> confirmForget());
        forget.setVisibility(RemoteStore.configured(this) ? View.VISIBLE : View.GONE);
        LinearLayout help = ui.card();
        ui.groupTitle(help, "电脑端设置");
        ui.paragraph(help, "在 Mac 的「MCP 服务 → 远程中继」填写相同的地址和指纹，以及电脑令牌，两端分别保存即可。也可用 Mac 的「同时配置手机」通过 adb 一次写入两端。");
        paintStatus();
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onStop() { token.setText(""); super.onStop(); }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }

    private void paintStatus() {
        boolean configured = RemoteStore.configured(this);
        boolean online = configured && RemoteStore.enabled(this) && PhoneRelayClient.connected();
        updatingRemote = true;
        remote.toggle.setChecked(configured && RemoteStore.enabled(this));
        updatingRemote = false;
        remote.setEnabled(configured && !busy);
        ui.paintOn(remote.mark, remote.toggle.isChecked());
        status.setText(!configured ? "未配置" : !RemoteStore.enabled(this) ? "已关闭" : online ? "已连接" : "连接中");
        status.setTextColor(online ? ui.held() : ui.muted());
    }

    private void showMessage(String value, boolean error) {
        message.setText(value);
        message.setTextColor(error ? getColor(R.color.error) : ui.held());
        message.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean value) {
        busy = value;
        endpoint.setEnabled(!value); pin.setEnabled(!value); token.setEnabled(!value);
        save.setEnabled(!value); forget.setEnabled(!value);
        save.setText(value ? "正在保存…" : "保存并连接");
        remote.setEnabled(!value && RemoteStore.configured(this));
    }

    private void save() {
        if (busy) { return; }
        String address = endpoint.getText().toString().trim();
        String fingerprint = pin.getText().toString().trim();
        String credential = token.getText().toString().trim();
        String error = RelayProfile.validationMessage(address, fingerprint, credential,
                RemoteStore.endpoint(this), RemoteStore.pin(this), RemoteStore.configured(this));
        if (error != null) { showMessage(error, true); return; }
        setBusy(true);
        showMessage("正在安全保存配置…", false);
        android.content.Context context = getApplicationContext();
        worker.execute(() -> {
            boolean saved = RemoteStore.configure(context, address, fingerprint, credential);
            if (saved) {
                KeeperStore.setMcpEnabled(context, true);
                FileMcpService.start(context);
            }
            runOnUiThread(() -> {
                if (isDestroyed()) { return; }
                setBusy(false);
                if (saved) {
                    endpoint.setText(RemoteStore.endpoint(this));
                    token.setText(""); token.setHint("留空保留已有令牌");
                    forget.setVisibility(View.VISIBLE);
                    showMessage("配置已保存，MCP 服务与远程通道已开启。连接状态见上方；尚未连上时请检查网络和中继资料。", false);
                } else { showMessage("配置未保存，请检查手机安全存储后重试。", true); }
                paintStatus();
            });
        });
    }

    private void confirmForget() {
        if (busy) { return; }
        new AlertDialog.Builder(this).setTitle("清除手机配置？")
                .setMessage("手机会停止连接中继，服务器地址和手机令牌将从本机移除。Mac 的配置需要在电脑上单独清除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) -> {
                    if (!RemoteStore.forget(this)) {
                        showMessage("配置未清除，请检查手机存储后重试。", true);
                        return;
                    }
                    if (KeeperStore.mcpEnabled(this)) { FileMcpService.start(this); }
                    else { FileMcpService.stop(this); }
                    endpoint.setText(""); pin.setText(""); token.setText("");
                    token.setHint("64 位十六进制令牌");
                    forget.setVisibility(View.GONE);
                    showMessage("手机配置已清除，本地 MCP 服务可继续使用。", false);
                    paintStatus();
                }).show();
    }
}
