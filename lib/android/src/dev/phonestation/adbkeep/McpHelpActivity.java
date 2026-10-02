package dev.phonestation.adbkeep;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 设置里的「MCP说明」。只给站在手机前的人看，不写工具名和令牌。 */
public final class McpHelpActivity extends Activity {
    private StationChrome ui;
    private TextView status;
    private TextView address;
    private TextView addressHint;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() { paint(); handler.postDelayed(this, 2_000L); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new StationChrome(this);
        ui.back("MCP 服务");
        LinearLayout info = ui.card();
        ui.groupTitle(info, "接入信息");
        status = ui.valueRow(info, "手机服务");
        address = ui.address(info);
        LinearLayout.LayoutParams addressParams = (LinearLayout.LayoutParams) address.getLayoutParams();
        addressParams.setMarginStart(ui.dp(16));
        address.setLayoutParams(addressParams);
        addressHint = ui.paragraph(info, "此地址仅在手机本机使用。电脑在菜单栏「MCP 服务」中复制接入信息，或运行 scripts/mcp.sh。");
        LinearLayout card = ui.card();
        McpHelp.Section[] sections = McpHelp.sections();
        for (int i = 0; i < sections.length; i++) {
            if (i > 0) {
                ui.hairline(card, 16);
            }
            addSection(ui, card, sections[i]);
        }
        paint();
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    private void paint() {
        boolean live = FileMcpService.listening();
        status.setText(live ? "运行中" : KeeperStore.mcpEnabled(this) ? "启动中" : "已关闭");
        status.setTextColor(live ? ui.held() : ui.muted());
        address.setVisibility(live ? View.VISIBLE : View.GONE);
        if (live) { address.setText(FileMcpService.listenUrl()); }
        addressHint.setText(live
                ? "此地址仅在手机本机使用。电脑在菜单栏「MCP 服务」中复制接入信息，或运行 scripts/mcp.sh。"
                : "回到首页打开 MCP 服务后，这里会显示接入信息。");
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
