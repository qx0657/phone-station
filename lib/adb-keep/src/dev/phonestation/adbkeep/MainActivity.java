package dev.phonestation.adbkeep;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Switch keepSwitch;
    private boolean updatingSwitch;
    private boolean active;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!active) {
                return;
            }
            render();
            handler.postDelayed(this, 2_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float density = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * density);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("无线调试保持");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title, matchWrap());

        TextView lead = new TextView(this);
        LinearLayout.LayoutParams leadParams = matchWrap();
        leadParams.topMargin = Math.round(12 * density);
        column.addView(lead, leadParams);
        lead.setText("Wi-Fi 连着、USB 调试还开着时，系统若把无线调试关掉，这里会再打开。");
        lead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

        keepSwitch = new Switch(this);
        keepSwitch.setText("自动打开");
        keepSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams switchParams = matchWrap();
        switchParams.topMargin = Math.round(20 * density);
        column.addView(keepSwitch, switchParams);
        keepSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingSwitch) {
                return;
            }
            KeeperStore.setEnabled(this, checked);
            if (checked) {
                KeeperService.start(this);
            } else {
                stopService(new android.content.Intent(this, KeeperService.class));
                KeeperAlarm.cancel(this);
            }
            render();
        });

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = Math.round(16 * density);
        column.addView(status, statusParams);

        TextView note = new TextView(this);
        note.setText("重启后要能自己起来，在荣耀的应用启动管理里把这个应用设成允许自启动、允许后台活动。系统里强行停止之后，需要再打开一次。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        TypedValue typed = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorSecondary, typed, true);
        note.setTextColor(typed.data);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = Math.round(20 * density);
        column.addView(note, noteParams);

        Button check = new Button(this);
        check.setText("现在检查");
        check.setAllCaps(false);
        check.setOnClickListener(view -> {
            if (KeeperStore.isEnabled(this)) {
                KeeperService.start(this);
            }
            render();
        });
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = Math.round(12 * density);
        buttonParams.gravity = Gravity.START;
        column.addView(check, buttonParams);

        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (KeeperStore.isEnabled(this)) {
            KeeperService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        render();
        handler.postDelayed(refresh, 2_000L);
    }

    @Override
    protected void onPause() {
        active = false;
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void render() {
        updatingSwitch = true;
        keepSwitch.setChecked(KeeperStore.isEnabled(this));
        updatingSwitch = false;
        String wifi = KeeperEngine.wifiHandle(this) == -1L ? "未连接" : "已连接";
        String permission = KeeperEngine.canWrite(this) ? "已授予" : "未授予";
        status.setText("状态  " + KeeperEngine.summary(this)
                + "\n无线调试  " + (KeeperEngine.wifiAdbEnabled(this) ? "开" : "关")
                + "\nUSB 调试  " + (KeeperEngine.adbEnabled(this) ? "开" : "关")
                + "\nWi-Fi  " + wifi
                + "\n写入权限  " + permission);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
