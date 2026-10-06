package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Infrequent setup stays out of the everyday home screen. */
public final class SettingsActivity extends StationActivity {
    private StationChrome ui;
    private TextView permissionValue;
    private TextView languageValue;
    private TextView themeValue;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new StationChrome(this);
        ui.back("设置");
        LinearLayout appearance = ui.card();
        ui.groupTitle(appearance, "语言与外观");
        languageValue = ui.linkRow(appearance, R.drawable.ic_language, "语言 / Language",
                view -> chooseAppearance(StationAppearance.LANGUAGE));
        ui.hairline(appearance, 62);
        themeValue = ui.linkRow(appearance, R.drawable.ic_theme, "主题",
                view -> chooseAppearance(StationAppearance.THEME));
        LinearLayout features = ui.card();
        ui.linkRow(features, R.drawable.ic_status_permission, "功能管理",
                view -> startActivity(new Intent(this, FeaturesActivity.class)));
        ui.hairline(features, 62);
        permissionValue = ui.linkRow(features, R.drawable.ic_status_permission, "权限与检查",
                view -> startActivity(new Intent(this, PermissionActivity.class)));
        ui.hairline(features, 62);
        ui.linkRow(features, R.drawable.ic_status_permission, "远程访问范围",
                view -> startActivity(RemotePermissionsActivity.intent(this, "")));
        LinearLayout connection = ui.card();
        ui.linkRow(connection, R.drawable.ic_status_wireless, "连接设置",
                view -> startActivity(new Intent(this, ConnectionActivity.class)));
        ui.hairline(connection, 62);
        TextView version = ui.linkRow(connection, R.drawable.ic_status_about, "关于",
                view -> startActivity(new Intent(this, AboutActivity.class)));
        try { version.setText(StationText.translate(AboutCopy.version(getPackageManager().getPackageInfo(getPackageName(), 0).versionName))); }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { version.setText(StationText.translate(AboutCopy.version(null))); }
    }

    @Override protected void onResume() {
        super.onResume();
        languageValue.setText(StationText.translate(languageLabel(StationAppearance.choice(this, StationAppearance.LANGUAGE))));
        themeValue.setText(StationText.translate(themeLabel(StationAppearance.choice(this, StationAppearance.THEME))));
        PermissionCopy.Board board = FeatureReadiness.board(this, "");
        permissionValue.setText(StationText.translate(board.summary));
        permissionValue.setTextColor(board.summaryTone == PermissionCopy.Tone.HELD ? ui.held() : ui.waiting());
    }

    private String languageLabel(String value) {
        return "zh".equals(value) ? "中文" : "en".equals(value) ? "English" : "跟随系统";
    }
    private String themeLabel(String value) {
        return "light".equals(value) ? "浅色" : "dark".equals(value) ? "深色" : "跟随系统";
    }
    private void chooseAppearance(String key) {
        boolean language = StationAppearance.LANGUAGE.equals(key);
        String[] values = language ? new String[] {"system", "zh", "en"} : new String[] {"system", "light", "dark"};
        String[] labels = language ? new String[] {"跟随系统", "中文", "English"} : new String[] {"跟随系统", "浅色", "深色"};
        int selected = java.util.Arrays.asList(values).indexOf(StationAppearance.choice(this, key));
        StationDialog.choices(this, language ? R.drawable.ic_language : R.drawable.ic_theme,
                language ? "语言 / Language" : "主题", labels, selected, (dialog, which) -> {
                    if (values[which].equals(StationAppearance.choice(this, key))) { dialog.dismiss(); return; }
                    if (!StationAppearance.save(this, key, values[which])) {
                        android.widget.Toast.makeText(this, StationText.translate("设置未保存，请重试。"), android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    AlertChannel.ensure(this);
                    StationNotifications.connectionChanged();
                    recreate();
                });
    }
}
