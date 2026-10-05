package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Infrequent setup stays out of the everyday home screen. */
public final class SettingsActivity extends Activity {
    private StationChrome ui;
    private TextView permissionValue;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new StationChrome(this);
        ui.back("设置");
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
        try { version.setText(AboutCopy.version(getPackageManager().getPackageInfo(getPackageName(), 0).versionName)); }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { version.setText(AboutCopy.version(null)); }
    }

    @Override protected void onResume() {
        super.onResume();
        PermissionCopy.Board board = FeatureReadiness.board(this, "");
        permissionValue.setText(board.summary);
        permissionValue.setTextColor(board.summaryTone == PermissionCopy.Tone.HELD ? ui.held() : ui.waiting());
    }
}
