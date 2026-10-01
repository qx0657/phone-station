package dev.phonestation.adbkeep;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 首页和子页共用的页面、卡片和行。 */
final class StationChrome {
    final Activity activity;
    final boolean night;
    final LinearLayout column;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    StationChrome(Activity activity) {
        this.activity = activity;
        night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        applySystemBars();

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(color(R.color.page));

        column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        int pad = dp(18);
        column.setPadding(pad, dp(8), pad, dp(28));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setContentView(scroll);
    }

    LinearLayout card() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        if (!night) {
            card.setElevation(dp(1) + activity.getResources().getDisplayMetrics().density * 0.5f);
        }
        card.setPadding(0, dp(6), 0, dp(6));
        LinearLayout.LayoutParams params = matchWrap();
        if (column.getChildCount() > 0) {
            params.topMargin = dp(14);
        }
        column.addView(card, params);
        return card;
    }

    void groupTitle(LinearLayout card, String label) {
        TextView title = text(20);
        title.setText(label);
        title.setTypeface(medium);
        title.setIncludeFontPadding(false);
        heading(title);
        int side = dp(16);
        title.setPadding(side, dp(12), side, dp(6));
        card.addView(title, matchWrap());
    }

    void back(String label) {
        LinearLayout bar = row();
        bar.setMinimumHeight(dp(48));
        bar.setPadding(0, dp(2), 0, dp(2));
        FrameLayout hit = new FrameLayout(activity);
        hit.setMinimumWidth(dp(40));
        hit.setMinimumHeight(dp(40));
        ImageView mark = glyph(R.drawable.ic_back, 22, ink());
        hit.addView(mark, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        hit.setContentDescription("返回");
        hit.setClickable(true);
        hit.setOnClickListener(view -> activity.finish());
        ripple(hit);
        bar.addView(hit, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(22);
        title.setText(label);
        title.setTypeface(medium);
        title.setIncludeFontPadding(false);
        heading(title);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(dp(4));
        bar.addView(title, titleParams);
        column.addView(bar, matchWrap());
    }

    Badge disc(int iconRes) {
        return badge(48, -1, iconRes, 24);
    }

    Mark mark(int iconRes) {
        Badge badge = badge(36, 11, iconRes, 20);
        return new Mark(badge.plate, badge.icon, badge.fill);
    }

    /** 只读的左右两列，没有图标。 */
    TextView valueRow(LinearLayout card, String label) {
        LinearLayout line = row();
        line.setMinimumHeight(dp(46));
        line.setPadding(dp(16), 0, dp(16), 0);
        TextView name = text(16);
        name.setText(label);
        name.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        line.addView(name, nameParams);
        TextView value = text(15);
        value.setIncludeFontPadding(false);
        value.setGravity(Gravity.END);
        value.setTextIsSelectable(true);
        line.addView(value, wrap());
        card.addView(line, matchWrap());
        return value;
    }

    /** 只读状态。没有底托，避免看起来能点。 */
    Fact fact(LinearLayout card, int iconRes, String label) {
        LinearLayout line = row();
        line.setMinimumHeight(dp(46));
        line.setPadding(dp(16), 0, dp(16), 0);
        ImageView icon = glyph(iconRes, 22, muted());
        line.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView name = text(16);
        name.setText(label);
        name.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.setMarginStart(dp(14));
        line.addView(name, nameParams);
        TextView value = text(15);
        value.setIncludeFontPadding(false);
        value.setGravity(Gravity.END);
        line.addView(value, wrap());
        card.addView(line, matchWrap());
        return new Fact(icon, value);
    }

    Control switchRow(LinearLayout card, int iconRes, String label) {
        Mark mark = mark(iconRes);
        LinearLayout line = row();
        line.setMinimumHeight(dp(56));
        line.setPadding(dp(14), 0, dp(8), 0);
        line.addView(mark.plate, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView name = text(16);
        name.setText(label);
        name.setIncludeFontPadding(false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.setMarginStart(dp(12));
        line.addView(name, nameParams);
        Switch toggle = new Switch(activity);
        toggle.setId(View.generateViewId());
        toggle.setSplitTrack(false);
        tintSwitch(toggle);
        name.setLabelFor(toggle.getId());
        line.addView(toggle, wrap());
        line.setClickable(true);
        line.setOnClickListener(view -> toggle.toggle());
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ripple(line);
        card.addView(line, matchWrap());
        paintOff(mark);
        return new Control(toggle, mark);
    }

    TextView linkRow(LinearLayout card, int iconRes, String label, View.OnClickListener click) {
        Mark mark = mark(iconRes);
        mark.fill.setColor(color(R.color.well));
        mark.icon.setColorFilter(ink(), PorterDuff.Mode.SRC_IN);
        LinearLayout line = row();
        line.setMinimumHeight(dp(56));
        line.setPadding(dp(14), 0, dp(10), 0);
        line.addView(mark.plate, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView name = text(16);
        name.setText(label);
        name.setIncludeFontPadding(false);
        name.setMaxLines(1);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.setMarginStart(dp(12));
        line.addView(name, nameParams);
        TextView value = text(15);
        value.setIncludeFontPadding(false);
        value.setGravity(Gravity.END);
        value.setMaxLines(1);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams valueParams = wrap();
        valueParams.setMarginStart(dp(8));
        line.addView(value, valueParams);
        ImageView chevron = glyph(R.drawable.ic_chevron, 18, muted());
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        chevronParams.setMarginStart(dp(2));
        line.addView(chevron, chevronParams);
        line.setClickable(true);
        line.setOnClickListener(click);
        ripple(line);
        card.addView(line, matchWrap());
        return value;
    }

    TextView address(LinearLayout card) {
        TextView view = text(13);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(muted());
        view.setIncludeFontPadding(false);
        view.setTextIsSelectable(true);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(10));
        chip.setColor(color(R.color.page));
        view.setBackground(chip);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMarginStart(dp(62));
        params.setMarginEnd(dp(14));
        params.bottomMargin = dp(8);
        card.addView(view, params);
        return view;
    }

    Notice notice(LinearLayout card) {
        TextView view = text(14);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0f, 1.25f);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(12));
        view.setBackground(fill);
        view.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMarginStart(dp(12));
        params.setMarginEnd(dp(12));
        params.topMargin = dp(4);
        params.bottomMargin = dp(8);
        card.addView(view, params);
        return new Notice(view, fill);
    }

    void hairline(LinearLayout parent, int startDp) {
        View line = new View(activity);
        line.setBackgroundColor(color(R.color.hairline));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        params.setMarginStart(dp(startDp));
        params.setMarginEnd(dp(16));
        parent.addView(line, params);
    }

    void paintOn(Mark mark, boolean on) {
        if (on) {
            mark.fill.setColor((held() & 0x00FFFFFF) | 0x1A000000);
            mark.icon.setColorFilter(held(), PorterDuff.Mode.SRC_IN);
            return;
        }
        paintOff(mark);
    }

    TextView text(float sp) {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(ink());
        return view;
    }

    int held() {
        return color(R.color.held);
    }

    int waiting() {
        return color(R.color.waiting);
    }

    int ink() {
        return resolve(android.R.attr.textColorPrimary);
    }

    int muted() {
        return color(R.color.muted);
    }

    int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void paintOff(Mark mark) {
        mark.fill.setColor(color(R.color.well));
        mark.icon.setColorFilter(muted(), PorterDuff.Mode.SRC_IN);
    }

    private Badge badge(int sizeDp, int radiusDp, int iconRes, int iconDp) {
        FrameLayout plate = new FrameLayout(activity);
        GradientDrawable fill = new GradientDrawable();
        if (radiusDp < 0) {
            fill.setShape(GradientDrawable.OVAL);
        } else {
            fill.setCornerRadius(dp(radiusDp));
        }
        fill.setColor(color(R.color.well));
        plate.setBackground(fill);
        plate.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        plate.setClipToOutline(true);
        ImageView icon = glyph(iconRes, iconDp, ink());
        plate.addView(icon, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
        return new Badge(plate, icon, fill);
    }

    private ImageView glyph(int res, int sizeDp, int tint) {
        ImageView view = new ImageView(activity);
        view.setImageResource(res);
        view.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setMinimumWidth(dp(sizeDp));
        view.setMinimumHeight(dp(sizeDp));
        return view;
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private void ripple(View view) {
        TypedValue selectable = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true);
        if (selectable.resourceId == 0) {
            return;
        }
        Drawable ripple = activity.getDrawable(selectable.resourceId);
        if (ripple != null) {
            view.setForeground(ripple);
        }
    }

    private void tintSwitch(Switch toggle) {
        int held = held();
        int thumbOff = night ? 0xFFE6E8ED : 0xFFFFFFFF;
        int trackOff = night ? 0xFF3A3D44 : 0xFFDCDFE6;
        int trackOn = (held & 0x00FFFFFF) | 0x99000000;
        int[][] states = new int[][] {
                new int[] {android.R.attr.state_checked},
                new int[] {}
        };
        toggle.setThumbTintList(new ColorStateList(states, new int[] {held, thumbOff}));
        toggle.setTrackTintList(new ColorStateList(states, new int[] {trackOn, trackOff}));
    }

    private void heading(TextView view) {
        if (Build.VERSION.SDK_INT >= 28) {
            view.setAccessibilityHeading(true);
        }
    }

    private int color(int res) {
        return activity.getColor(res);
    }

    private int resolve(int attribute) {
        TypedValue typed = new TypedValue();
        activity.getTheme().resolveAttribute(attribute, typed, true);
        if (typed.resourceId != 0) {
            return activity.getColor(typed.resourceId);
        }
        return typed.data;
    }

    private void applySystemBars() {
        if (Build.VERSION.SDK_INT >= 29) {
            activity.getWindow().setStatusBarContrastEnforced(false);
            activity.getWindow().setNavigationBarContrastEnforced(false);
        }
        View decor = activity.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(night ? 0 : mask, mask);
            }
            return;
        }
        int flags = decor.getSystemUiVisibility();
        if (night) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    /** 横向一行里的右侧控件。用 match_parent 会占满整行，左侧标签宽度变成 0。 */
    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static final class Badge {
        final FrameLayout plate;
        final ImageView icon;
        final GradientDrawable fill;

        Badge(FrameLayout plate, ImageView icon, GradientDrawable fill) {
            this.plate = plate;
            this.icon = icon;
            this.fill = fill;
        }
    }

    static final class Mark {
        final FrameLayout plate;
        final ImageView icon;
        final GradientDrawable fill;

        Mark(FrameLayout plate, ImageView icon, GradientDrawable fill) {
            this.plate = plate;
            this.icon = icon;
            this.fill = fill;
        }
    }

    static final class Fact {
        final ImageView icon;
        final TextView value;

        Fact(ImageView icon, TextView value) {
            this.icon = icon;
            this.value = value;
        }
    }

    static final class Control {
        final Switch toggle;
        final Mark mark;

        Control(Switch toggle, Mark mark) {
            this.toggle = toggle;
            this.mark = mark;
        }
    }

    static final class Notice {
        final TextView view;
        final GradientDrawable fill;

        Notice(TextView view, GradientDrawable fill) {
            this.view = view;
            this.fill = fill;
        }
    }
}
