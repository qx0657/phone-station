package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 会话提醒用哪段铃声。点一项就选定，并试听。静音不播。 */
public final class AlertToneActivity extends StationActivity {
    private StationChrome ui;
    private LinearLayout rows;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new StationChrome(this);
        ui.back("电脑提醒铃声");
        LinearLayout card = ui.card();
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        card.addView(rows, matchWrap());
        render();
    }

    private void render() {
        rows.removeAllViews();
        String stored = KeeperStore.alertSound(this);
        addRow("跟随系统", null, stored == null);
        addRow("静音", AlertSoundPlan.SILENT_TOKEN, AlertSoundPlan.SILENT_TOKEN.equals(stored));
        Cursor cursor = null;
        try {
            RingtoneManager manager = new RingtoneManager(this);
            manager.setType(RingtoneManager.TYPE_NOTIFICATION);
            cursor = manager.getCursor();
            if (cursor == null) {
                return;
            }
            boolean pathHit = false;
            for (int i = 0; i < cursor.getCount(); i++) {
                if (!cursor.moveToPosition(i)) {
                    continue;
                }
                String title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX);
                Uri uri = manager.getRingtoneUri(i);
                if (title == null || title.isEmpty() || uri == null) {
                    continue;
                }
                boolean selected = AlertSoundPlan.sameTone(stored, uri.toString());
                if (selected) {
                    pathHit = true;
                }
                addRow(title, uri.toString(), selected);
            }
            if (!pathHit) {
                markByTitle(stored);
            }
        } catch (RuntimeException error) {
            Log.w(KeeperEngine.TAG, "alert tones " + error);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** 地址对不上时，用铃声标题对上那一行。静音和跟随系统不走这里。 */
    private void markByTitle(String stored) {
        if (stored == null || stored.isEmpty() || AlertSoundPlan.SILENT_TOKEN.equals(stored)) {
            return;
        }
        String title;
        try {
            Ringtone tone = RingtoneManager.getRingtone(this, Uri.parse(stored));
            if (tone == null) {
                return;
            }
            title = tone.getTitle(this);
        } catch (RuntimeException error) {
            return;
        }
        if (title == null || title.isEmpty() || "跟随系统".equals(title) || "静音".equals(title)) {
            return;
        }
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (!title.equals(child.getTag())) {
                continue;
            }
            markSelected((LinearLayout) child);
        }
    }

    private void addRow(String label, String stored, boolean selected) {
        if (rows.getChildCount() > 0) {
            ui.hairline(rows, 16);
        }
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(ui.dp(52));
        line.setPadding(ui.dp(8), 0, ui.dp(12), 0);
        line.setTag(label);

        TextView name = ui.text(16);
        name.setText(stored == null || AlertSoundPlan.SILENT_TOKEN.equals(stored) ? StationText.translate(label) : label);
        name.setIncludeFontPadding(false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView check = new ImageView(this);
        check.setImageResource(R.drawable.ic_check);
        check.setColorFilter(ui.held(), PorterDuff.Mode.SRC_IN);
        check.setContentDescription(StationText.translate("已选择"));
        check.setVisibility(View.GONE);
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22));
        checkParams.setMarginStart(ui.dp(12));
        line.addView(check, checkParams);

        if (selected) {
            markSelected(line);
        }
        TypedValue selectable = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true);
        if (selectable.resourceId != 0) {
            Drawable ripple = getDrawable(selectable.resourceId);
            if (ripple != null) {
                line.setForeground(ripple);
            }
        }
        line.setClickable(true);
        line.setOnClickListener(view -> choose(stored));
        LinearLayout.LayoutParams params = matchWrap();
        params.setMarginStart(ui.dp(8));
        params.setMarginEnd(ui.dp(8));
        rows.addView(line, params);
    }

    private void markSelected(LinearLayout line) {
        TextView name = (TextView) line.getChildAt(0);
        ImageView check = (ImageView) line.getChildAt(1);
        name.setTextColor(ui.held());
        name.setTypeface(medium);
        check.setVisibility(View.VISIBLE);
        GradientDrawable wash = new GradientDrawable();
        wash.setCornerRadius(ui.dp(12));
        wash.setColor((ui.held() & 0x00FFFFFF) | 0x16000000);
        line.setBackground(wash);
    }

    private void choose(String stored) {
        KeeperStore.setAlertSound(this, stored);
        preview(stored);
        finish();
    }

    private void preview(String stored) {
        String system = Settings.System.getString(
                getContentResolver(), Settings.System.NOTIFICATION_SOUND);
        AlertSoundPlan plan = AlertSound.openable(
                this, AlertSoundPlan.choose(null, stored, system));
        if (plan.kind == AlertSoundPlan.SILENT || plan.kind == AlertSoundPlan.MISSING) {
            return;
        }
        Context app = getApplicationContext();
        new Thread(() -> AlertSound.play(app, plan), "alert-preview").start();
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
