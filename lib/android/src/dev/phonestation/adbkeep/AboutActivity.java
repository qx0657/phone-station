package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 设置里的「关于」。版本来自安装包，作者写在 {@link AboutCopy}。 */
public final class AboutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StationChrome ui = new StationChrome(this);
        ui.back("关于");
        LinearLayout card = ui.card();
        TextView version = ui.valueRow(card, "版本");
        version.setText(versionName());
        ui.hairline(card, 16);
        TextView author = ui.valueRow(card, "作者");
        author.setText(AboutCopy.AUTHOR);
    }

    private String versionName() {
        try {
            return AboutCopy.version(
                    getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException error) {
            return AboutCopy.version(null);
        }
    }
}
