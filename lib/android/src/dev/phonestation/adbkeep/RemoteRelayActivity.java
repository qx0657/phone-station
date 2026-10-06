package dev.phonestation.adbkeep;

import android.app.Activity;
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
public final class RemoteRelayActivity extends StationActivity {
    private StationChrome ui;
    private EditText endpoint;
    private EditText pin;
    private EditText token;
    private TextView status;
    private StationChrome.Control remote;
    private boolean updatingRemote;
    private StationChrome.Link permissionLink;
    private StationChrome.Link configLink;
    private LinearLayout configFields;
    private boolean configExpanded;
    private TextView message;
    private Button save;
    private Button forget;
    private boolean busy;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable connectionChanged = this::paintStatus;
    private final Runnable refresh = new Runnable() {
        public void run() { paintStatus(); handler.postDelayed(this, 1_000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        ui = new StationChrome(this);
        ui.back("远程连接");
        LinearLayout overview = ui.card();
        remote = ui.switchRow(overview, R.drawable.ic_status_cloud, "启用远程连接");
        remote.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updatingRemote || busy) { return; }
            if (!RemoteStore.setEnabled(this, checked)) {
                showMessage("远程开关未保存，请检查手机存储。", true); paintStatus(); return;
            }
            if (KeeperStore.mcpEnabled(this) || RemoteStore.enabled(this)) { FileMcpService.start(this); }
            else { FileMcpService.stop(this); }
            paintStatus();
        });
        status = ui.valueRow(overview, "连接状态");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.paragraph(overview, "让电脑通过互联网使用这台手机的 MCP 工具。手机主动连接中继，Wi-Fi 和移动网络都可使用。");
        ui.hairline(overview, 16);
        permissionLink = ui.statusLinkRow(overview, R.drawable.ic_status_permission, "远程访问范围",
                view -> startActivity(RemotePermissionsActivity.intent(this, "")));
        message = ui.paragraph(overview, "");
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setVisibility(View.GONE);
        LinearLayout configCard = ui.card();
        configExpanded = state != null ? state.getBoolean("config_expanded") : !RemoteStore.configured(this);
        configLink = ui.statusLinkRow(configCard, R.drawable.ic_status_cloud, "中继配置", view -> {
            configExpanded = !configExpanded; paintConfig();
        });
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        configFields = form;
        configCard.addView(form);
        endpoint = ui.field(form, "服务器地址", "https://relay.example.com", false);
        endpoint.setText(RemoteStore.endpoint(this));
        pin = ui.field(form, "证书指纹 · SPKI SHA-256", "64 位十六进制指纹", false);
        pin.setText(RemoteStore.pin(this));
        if (state != null) {
            endpoint.setText(state.getString("endpoint_draft", RemoteStore.endpoint(this)));
            pin.setText(state.getString("pin_draft", RemoteStore.pin(this)));
        }
        token = ui.field(form, "手机令牌", RemoteStore.configured(this) ? "留空保留已有令牌" : "64 位十六进制令牌", true);
        token.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        token.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) { return false; }
            save(); return true;
        });
        endpoint.setNextFocusForwardId(pin.getId());
        pin.setNextFocusForwardId(token.getId());
        ui.paragraph(form, "资料由中继管理员提供。这里填手机令牌；Mac 使用另一枚电脑令牌。更换地址或指纹时需重新填写令牌。");
        save = ui.action(form, "保存并连接", true, view -> save());
        forget = ui.action(form, "清除手机配置", false, view -> confirmForget());
        forget.setVisibility(RemoteStore.configured(this) ? View.VISIBLE : View.GONE);
        LinearLayout help = ui.card();
        ui.groupTitle(help, "电脑端设置");
        ui.paragraph(help, "在 Mac 的「设置 → 电脑接入 → 远程中继」填写相同的地址和指纹，以及电脑令牌，两端分别保存即可。也可用 Mac 的「同时配置手机」通过 adb 一次写入两端。");
        paintStatus();
    }

    @Override protected void onResume() {
        super.onResume(); StationConnectionEvents.add(connectionChanged); handler.post(refresh);
    }
    @Override protected void onPause() {
        StationConnectionEvents.remove(connectionChanged); handler.removeCallbacks(refresh); super.onPause();
    }
    @Override protected void onStop() { token.setText(""); super.onStop(); }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }

    private void paintStatus() {
        boolean configured = RemoteStore.configured(this);
        boolean online = StationFeatures.master(this) && configured && RemoteStore.enabled(this) && PhoneRelayClient.connected();
        updatingRemote = true;
        remote.toggle.setChecked(configured && RemoteStore.enabled(this));
        updatingRemote = false;
        remote.setEnabled(configured && !busy);
        permissionLink.value.setText(StationText.translate(RemoteStore.permissionSummary(this)));
        permissionLink.value.setTextColor(ui.muted());
        paintConfig();
        ui.paintOn(remote.mark, remote.toggle.isChecked());
        String label = PhoneRelayClient.connectionLabel(this);
        if (!label.contentEquals(status.getText())) { status.setText(StationText.translate(label)); }
        status.setTextColor(online ? ui.held() : PhoneRelayClient.checking() ? ui.waiting() : ui.muted());
    }

    private void showMessage(String value, boolean error) {
        message.setText(StationText.translate(value));
        message.setTextColor(error ? getColor(R.color.error) : ui.held());
        message.setVisibility(View.VISIBLE);
    }

    private void paintConfig() {
        configFields.setVisibility(configExpanded ? View.VISIBLE : View.GONE);
        configLink.value.setText(StationText.translate(RemoteStore.configured(this) ? "已保存" : "未配置"));
        configLink.chevron.setRotation(configExpanded ? 270 : 90);
        configLink.row.setContentDescription(StationText.translate("中继配置，" + configLink.value.getText() + "，" + (configExpanded ? "收起" : "展开")));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("endpoint_draft", endpoint.getText().toString());
        state.putString("pin_draft", pin.getText().toString());
        state.putBoolean("config_expanded", configExpanded); super.onSaveInstanceState(state);
    }

    private void setBusy(boolean value) {
        busy = value;
        endpoint.setEnabled(!value); pin.setEnabled(!value); token.setEnabled(!value);
        save.setEnabled(!value); forget.setEnabled(!value);
        save.setText(StationText.translate(value ? "正在保存…" : "保存并连接"));
        remote.setEnabled(!value && RemoteStore.configured(this));
        configLink.row.setEnabled(!value);
    }

    private void save() {
        if (busy) { return; }
        String address = endpoint.getText().toString().trim();
        String fingerprint = pin.getText().toString().trim();
        String credential = token.getText().toString().trim();
        String error = RelayProfile.validationMessage(address, fingerprint, credential,
                RemoteStore.endpoint(this), RemoteStore.pin(this), RemoteStore.configured(this));
        if (error != null) { configExpanded = true; paintConfig(); showMessage(error, true); return; }
        setBusy(true);
        showMessage("正在安全保存配置…", false);
        android.content.Context context = getApplicationContext();
        worker.execute(() -> {
            boolean saved = RemoteStore.configure(context, address, fingerprint, credential);
            if (saved) {
                FileMcpService.start(context);
            }
            runOnUiThread(() -> {
                if (isDestroyed()) { return; }
                setBusy(false);
                if (saved) {
                    endpoint.setText(RemoteStore.endpoint(this));
                    token.setText(""); token.setHint(StationText.translate("留空保留已有令牌"));
                    forget.setVisibility(View.VISIBLE);
                    showMessage(StationFeatures.master(this) ? "配置已保存，正在连接。连接结果见上方状态。" : "配置已保存，恢复手机工位后连接。", false);
                } else { showMessage("配置未保存，请检查手机安全存储后重试。", true); }
                paintStatus();
            });
        });
    }

    private void confirmForget() {
        if (busy) { return; }
        StationDialog.confirm(this, R.drawable.ic_status_cloud, "清除手机配置？",
                "手机会停止连接中继，服务器地址和手机令牌将从本机移除。Mac 的配置需要在电脑上单独清除。",
                "取消", "清除", true, () -> {
                    if (!RemoteStore.forget(this)) {
                        showMessage("配置未清除，请检查手机存储后重试。", true);
                        return;
                    }
                    if (KeeperStore.mcpEnabled(this)) { FileMcpService.start(this); }
                    else { FileMcpService.stop(this); }
                    configExpanded = true;
                    endpoint.setText(""); pin.setText(""); token.setText("");
                    token.setHint(StationText.translate("64 位十六进制令牌"));
                    forget.setVisibility(View.GONE);
                    showMessage("手机配置已清除，本地 MCP 服务可继续使用。", false);
                    paintStatus();
                });
    }
}
