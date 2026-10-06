package dev.phonestation.adbkeep;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** App-owned content in a native dialog window, with normal Back/focus/dismiss behavior. */
final class StationDialog {
    interface Choice { void selected(Dialog dialog, int index); }

    static void choices(Activity activity, int icon, String title, String[] labels, int selected, Choice action) {
        StationDialog panel = new StationDialog(activity, icon, title, false);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            CheckedTextView row = new CheckedTextView(activity);
            row.setText(StationText.translate(labels[i]));
            panel.styleText(row, 16, panel.ink());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(panel.dp(16), panel.dp(13), panel.dp(16), panel.dp(13));
            row.setMinHeight(panel.dp(52));
            row.setChecked(i == selected);
            row.setTypeface(Typeface.create(i == selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            Drawable check = activity.getDrawable(R.drawable.ic_check).mutate();
            check.setTint(panel.accent);
            // Keep the label aligned when a different item is selected.
            check.setAlpha(i == selected ? 255 : 0);
            row.setCheckMarkDrawable(check);
            row.setCheckMarkTintList(ColorStateList.valueOf(panel.accent));
            row.setBackground(panel.surface(i == selected ? panel.wash(panel.accent, 18) : panel.color(R.color.well),
                    i == selected ? panel.accent : Color.TRANSPARENT));
            row.setFocusable(true);
            row.setOnClickListener(view -> action.selected(panel.dialog, index));
            panel.add(row, 8);
        }
        panel.button("取消", false, () -> panel.dialog.dismiss());
        panel.show();
    }

    static void confirm(Activity activity, int icon, String title, String message,
                        String cancel, String action, boolean destructive, Runnable confirmed) {
        StationDialog panel = new StationDialog(activity, icon, title, destructive);
        TextView copy = new TextView(activity);
        panel.styleText(copy, 15, panel.color(R.color.muted));
        copy.setText(StationText.translate(message));
        copy.setLineSpacing(panel.dp(3), 1.05f);
        panel.add(copy, 0);
        panel.button(action, true, () -> {
            panel.dialog.dismiss();
            confirmed.run();
        });
        panel.button(cancel, false, () -> panel.dialog.dismiss());
        panel.show();
    }

    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout content;
    private final int accent;
    private StationDialog(Activity activity, int iconRes, String title, boolean destructive) {
        this.activity = activity;
        accent = color(destructive ? R.color.error : R.color.held);
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setOwnerActivity(activity);
        dialog.setTitle(StationText.translate(title));
        dialog.setCanceledOnTouchOutside(true);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(22), dp(22), dp(22));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout badge = new FrameLayout(activity);
        badge.setBackground(surface(wash(accent, 20), Color.TRANSPARENT));
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(accent));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        badge.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        header.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView heading = new TextView(activity);
        styleText(heading, 20, ink());
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setText(StationText.translate(title));
        heading.setAccessibilityHeading(true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
        headingParams.leftMargin = dp(14);
        header.addView(heading, headingParams);
        add(header, 18);
        content.setAccessibilityPaneTitle(StationText.translate(title));
    }

    private void button(String label, boolean primary, Runnable action) {
        Button button = new Button(activity);
        styleText(button, 15, primary ? color(R.color.on_held) : ink());
        button.setText(StationText.translate(label));
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setBackground(surface(primary ? accent : color(R.color.well), Color.TRANSPARENT));
        button.setStateListAnimator(null);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(primary || content.getChildCount() > 1 && content.getChildAt(content.getChildCount() - 1) instanceof CheckedTextView ? 16 : 8);
        content.addView(button, params);
    }

    private void show() {
        if (activity.isFinishing() || activity.isDestroyed()) { return; }
        Window window = dialog.getWindow();
        Rect bounds = Build.VERSION.SDK_INT >= 30
                ? activity.getWindowManager().getCurrentWindowMetrics().getBounds()
                : new Rect(0, 0, activity.getResources().getDisplayMetrics().widthPixels,
                        activity.getResources().getDisplayMetrics().heightPixels);
        int insetHeight = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets insets = activity.getWindowManager().getCurrentWindowMetrics()
                    .getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            insetHeight = insets.top + insets.bottom;
        }
        final int maxHeight = Math.max(dp(48), bounds.height() - insetHeight - dp(48));
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int width, int height) {
                int limit = View.MeasureSpec.getMode(height) == View.MeasureSpec.UNSPECIFIED
                        ? maxHeight : Math.min(maxHeight, View.MeasureSpec.getSize(height));
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        scroll.setBackground(rounded(color(R.color.card), 16, Color.TRANSPARENT));
        scroll.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        scroll.setClipToOutline(true);
        scroll.addView(content);
        dialog.setContentView(scroll);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.38f);
        dialog.show();
        window.setLayout(Math.min(dp(480), bounds.width() - dp(32)), WindowManager.LayoutParams.WRAP_CONTENT);
        if (activity instanceof StationActivity) { ((StationActivity) activity).trackDialog(dialog); }
    }

    private void add(View view, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(bottom);
        content.addView(view, params);
    }
    private void styleText(TextView view, int sp, int tint) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(tint);
    }
    private Drawable surface(int fill, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(wash(ink(), 22)), rounded(fill, 12, stroke), rounded(Color.WHITE, 12, Color.TRANSPARENT));
    }
    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) { shape.setStroke(dp(1), stroke); }
        return shape;
    }
    private int color(int resource) { return activity.getColor(resource); }
    private int ink() {
        TypedValue value = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true);
        return value.resourceId == 0 ? value.data : color(value.resourceId);
    }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private int wash(int tint, int alpha) { return Color.argb(alpha, Color.red(tint), Color.green(tint), Color.blue(tint)); }
}
