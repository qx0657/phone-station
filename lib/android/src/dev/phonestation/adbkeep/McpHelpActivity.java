package dev.phonestation.adbkeep;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 设置里的「MCP说明」。只给站在手机前的人看，不写工具名和令牌。 */
public final class McpHelpActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StationChrome ui = new StationChrome(this);
        ui.back("MCP服务");
        LinearLayout card = ui.card();
        McpHelp.Section[] sections = McpHelp.sections();
        for (int i = 0; i < sections.length; i++) {
            if (i > 0) {
                ui.hairline(card, 16);
            }
            addSection(ui, card, sections[i]);
        }
    }

    private void addSection(StationChrome ui, LinearLayout card, McpHelp.Section section) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        int side = ui.dp(16);
        block.setPadding(side, ui.dp(12), side, ui.dp(12));

        TextView title = ui.text(16);
        title.setText(section.title);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= 28) {
            title.setAccessibilityHeading(true);
        }
        block.addView(title, matchWrap());

        TextView body = ui.text(15);
        body.setText(section.body);
        body.setIncludeFontPadding(false);
        body.setLineSpacing(0f, 1.3f);
        body.setTextIsSelectable(true);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = ui.dp(6);
        block.addView(body, bodyParams);
        card.addView(block, matchWrap());
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
