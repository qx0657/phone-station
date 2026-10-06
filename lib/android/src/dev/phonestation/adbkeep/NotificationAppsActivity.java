package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 应用白名单逐项保存，没有默认全选。搜索不改变已选择的应用。 */
public final class NotificationAppsActivity extends StationActivity {
    private StationChrome ui;
    private EditText search;
    private TextView count;
    private TextView empty;
    private LinearLayout list;
    private final List<App> apps = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable filter = this::render;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new StationChrome(this);
        ui.back("选择同步应用");
        LinearLayout controls = ui.card();
        count = ui.paragraph(controls, "正在读取应用…");
        search = ui.field(controls, "搜索应用", "应用名称或包名", false);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        ui.paragraph(controls, "勾选即保存，取消后立即停止同步该应用。新安装的应用默认不选。");
        LinearLayout card = ui.card();
        empty = ui.paragraph(card, "正在读取应用…");
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        card.addView(list, new LinearLayout.LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int before, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int after) {
                handler.removeCallbacks(filter); handler.postDelayed(filter, 120);
            }
            public void afterTextChanged(Editable value) {}
        });
        worker.execute(this::load);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        PackageManager manager = getPackageManager();
        List<App> loaded = new ArrayList<>();
        Set<String> found = new HashSet<>();
        Set<String> selected = PhoneNotifications.selected(this);
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo item : manager.queryIntentActivities(launch, 0)) {
                String pkg = item.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !found.add(pkg)) { continue; }
                ApplicationInfo info = item.activityInfo.applicationInfo;
                loaded.add(new App(pkg, manager.getApplicationLabel(info).toString(), manager.getApplicationIcon(info)));
            }
            // 已选应用即使移除了桌面入口仍可取消；卸载的条目保留包名，方便清理选择。
            for (String pkg : selected) {
                if (!found.add(pkg) || pkg.equals(getPackageName())) { continue; }
                try {
                    ApplicationInfo info = manager.getApplicationInfo(pkg, 0);
                    loaded.add(new App(pkg, manager.getApplicationLabel(info).toString(), manager.getApplicationIcon(info)));
                } catch (PackageManager.NameNotFoundException missing) {
                    loaded.add(new App(pkg, pkg + "（不可用）", manager.getDefaultActivityIcon()));
                }
            }
            Collator order = Collator.getInstance();
            loaded.sort((left, right) -> selected.contains(left.pkg) != selected.contains(right.pkg)
                    ? (selected.contains(left.pkg) ? -1 : 1) : order.compare(left.label, right.label));
            handler.post(() -> {
                if (isDestroyed() || isFinishing()) { return; }
                apps.addAll(loaded);
                render();
            });
        } catch (RuntimeException error) {
            handler.post(() -> {
                if (isDestroyed()) { return; }
                count.setText(StationText.translate("应用列表读取失败"));
                empty.setText(StationText.translate("请返回后重新打开「选择应用」"));
            });
        }
    }

    private void render() {
        if (isDestroyed()) { return; }
        Set<String> selected = PhoneNotifications.selected(this);
        count.setText(StationText.translate("已选 " + selected.size() + " 个应用"));
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        list.removeAllViews();
        int shown = 0;
        for (App app : apps) {
            if (!app.label.toLowerCase(Locale.ROOT).contains(query) && !app.pkg.toLowerCase(Locale.ROOT).contains(query)) { continue; }
            if (shown++ > 0) { ui.hairline(list, 62); }
            StationChrome.Control row = ui.switchRow(list, R.drawable.ic_status_notify, app.label, false);
            row.mark.icon.setImageDrawable(app.icon);
            row.mark.icon.clearColorFilter();
            row.toggle.setChecked(selected.contains(app.pkg));
            row.toggle.setContentDescription(StationText.translate("同步 " + app.label + " 的通知"));
            row.toggle.setOnCheckedChangeListener((button, on) -> {
                PhoneNotifications.select(this, app.pkg, on);
                count.setText(StationText.translate("已选 " + PhoneNotifications.selected(this).size() + " 个应用"));
            });
        }
        empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        empty.setText(StationText.translate(query.isEmpty() ? "没有可选择的应用" : "没有匹配的应用，试试名称或包名"));
    }
    private static final class App {
        final String pkg, label;
        final Drawable icon;
        App(String pkg, String label, Drawable icon) { this.pkg = pkg; this.label = label; this.icon = icon; }
    }
}
