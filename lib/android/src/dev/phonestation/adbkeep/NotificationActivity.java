package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 会话提醒的弹出和铃声。常驻状态那条不在这里。 */
public final class NotificationActivity extends Activity {
    private StationChrome ui;
    private StationChrome.Control alert;
    private TextView soundValue;
    private boolean updatingAlert;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new StationChrome(this);
        ui.back("通知");

        LinearLayout card = ui.card();
        alert = ui.switchRow(card, R.drawable.ic_status_notify, "提醒弹出");
        alert.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updatingAlert) {
                return;
            }
            KeeperStore.setAlertPops(this, checked);
            AlertChannel.ensure(this);
            ui.paintOn(alert.mark, checked);
        });
        updatingAlert = true;
        alert.toggle.setChecked(KeeperStore.alertPops(this));
        updatingAlert = false;
        ui.paintOn(alert.mark, alert.toggle.isChecked());

        ui.hairline(card, 62);
        soundValue = ui.linkRow(card, R.drawable.ic_status_tone, "铃声", view -> pickSound());
        soundValue.setMaxWidth(ui.dp(148));
        soundValue.setEllipsize(TextUtils.TruncateAt.END);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlertChannel.ensure(this);
        updatingAlert = true;
        alert.toggle.setChecked(KeeperStore.alertPops(this));
        updatingAlert = false;
        ui.paintOn(alert.mark, alert.toggle.isChecked());
        soundValue.setText(soundLabel(KeeperStore.alertSound(this)));
    }

    private void pickSound() {
        startActivity(new Intent(this, AlertToneActivity.class));
    }

    private String soundLabel(String stored) {
        if (AlertSoundPlan.SILENT_TOKEN.equals(stored)) {
            return "静音";
        }
        if (stored == null || stored.isEmpty()) {
            return "跟随系统";
        }
        try {
            Ringtone tone = RingtoneManager.getRingtone(this, Uri.parse(stored));
            if (tone == null) {
                return "找不到";
            }
            String title = tone.getTitle(this);
            if (title == null || title.isEmpty()) {
                return "已选择";
            }
            return title;
        } catch (Exception error) {
            return "找不到";
        }
    }
}
