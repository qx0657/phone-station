package dev.phonestation.adbkeep;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.util.Collections;

/** 同一窗口内的侧栏：位移和遮罩随手指变化，背景延伸到系统栏。 */
final class StationDrawer {
    private final StationChrome ui;
    private final DrawerHost host;
    private final ScrollView panel;
    private final View scrim;
    private final ImageButton menu;
    private final ImageButton dismiss;
    private final LinearLayout destinations;
    private ValueAnimator animator;
    private float offset;
    private boolean visible;
    private boolean disposed;
    private OnBackInvokedCallback backCallback;

    StationDrawer(StationChrome ui) {
        this.ui = ui;
        LinearLayout bar = bar();
        menu = icon(R.drawable.ic_menu, "打开侧边栏", view -> open());
        bar.addView(menu, square());
        bar.addView(title("手机工位"), weighted());
        ui.column.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        panel = new ScrollView(ui.activity);
        panel.setFillViewport(true);
        panel.setBackgroundColor(ui.activity.getColor(R.color.card));
        panel.setElevation(ui.dp(12));
        panel.setVisibility(View.INVISIBLE);
        panel.setAccessibilityPaneTitle("手机工位");
        destinations = new LinearLayout(ui.activity);
        destinations.setOrientation(LinearLayout.VERTICAL);
        destinations.setPadding(0, ui.dp(8), 0, ui.dp(24));
        LinearLayout heading = bar();
        heading.setPadding(ui.dp(20), 0, ui.dp(8), 0);
        heading.addView(title("手机工位"), weighted());
        dismiss = icon(R.drawable.ic_close, "关闭侧边栏", view -> close());
        heading.addView(dismiss, square());
        destinations.addView(heading);
        ui.hairline(destinations, 20);
        TextView section = ui.text(14);
        section.setText(StationText.translate("设置"));
        section.setTextColor(ui.muted());
        section.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(6));
        section.setAccessibilityHeading(true);
        destinations.addView(section);
        panel.addView(destinations, new ScrollView.LayoutParams(-1, -2));
        panel.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        scrim = new View(ui.activity);
        scrim.setBackgroundColor(0xff000000);
        scrim.setContentDescription(StationText.translate("关闭侧边栏"));
        scrim.setOnClickListener(view -> close());
        scrim.setVisibility(View.GONE);
        host = new DrawerHost();
        ((ViewGroup) ui.scroll.getParent()).removeView(ui.scroll);
        host.addView(ui.scroll, new FrameLayout.LayoutParams(-1, -1));
        host.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        int width = Math.min(ui.dp(320), ui.activity.getResources().getDisplayMetrics().widthPixels - ui.dp(56));
        host.addView(panel, new FrameLayout.LayoutParams(width, -1, Gravity.START));
        ui.activity.setContentView(host);
        if (Build.VERSION.SDK_INT >= 30) { ui.activity.getWindow().setDecorFitsSystemWindows(false); }
        else {
            View decor = ui.activity.getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        host.requestApplyInsets();
    }

    TextView destination(int icon, String label, Class<?> activity) {
        return ui.linkRow(destinations, icon, label, view -> {
            stopAnimation();
            setOffset(0f);
            ui.activity.startActivity(new Intent(ui.activity, activity));
        });
    }

    boolean isVisible() { return visible; }
    void open() { if (!ui.activity.isFinishing()) { settle(true); } }
    void close() { settle(false); }
    void restoreOpen() { host.post(() -> { if (!disposed) { setOffset(1f); } }); }

    void dispose() {
        disposed = true;
        stopAnimation();
        unregisterBack();
        host.releaseVelocity();
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void settle(boolean open) {
        stopAnimation();
        if (disposed) { return; }
        float target = open ? 1f : 0f;
        if (!ValueAnimator.areAnimatorsEnabled() || offset == target) {
            setOffset(target);
            return;
        }
        ValueAnimator next = ValueAnimator.ofFloat(offset, target);
        animator = next;
        next.setDuration(120L + Math.round(140f * Math.abs(target - offset)));
        next.setInterpolator(new DecelerateInterpolator(2f));
        next.addUpdateListener(animation -> {
            setOffset((float) animation.getAnimatedValue());
            if (offset == target) { (open ? dismiss : menu).requestFocus(); }
        });
        next.start();
    }

    private void setOffset(float value) {
        offset = Math.max(0f, Math.min(1f, value));
        boolean nowVisible = offset > 0f;
        if (nowVisible != visible) {
            visible = nowVisible;
            panel.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            scrim.setVisibility(visible ? View.VISIBLE : View.GONE);
            ui.scroll.setImportantForAccessibility(visible
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (visible) { registerBack(); } else { unregisterBack(); }
            host.updateEdgeExclusion();
        }
        panel.setTranslationX((offset - 1f) * panel.getWidth() * host.direction());
        scrim.setAlpha(offset * 0.32f);
    }

    private void registerBack() {
        if (Build.VERSION.SDK_INT < 33 || backCallback != null) { return; }
        backCallback = Build.VERSION.SDK_INT >= 34 ? predictiveBack() : this::close;
        ui.activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }

    private void unregisterBack() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            ui.activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
    }

    private OnBackInvokedCallback predictiveBack() {
        return new OnBackAnimationCallback() {
            private float start;
            @Override public void onBackStarted(BackEvent event) { stopAnimation(); start = offset; }
            @Override public void onBackProgressed(BackEvent event) {
                setOffset(Math.max(0.001f, start * (1f - event.getProgress())));
            }
            @Override public void onBackCancelled() { settle(true); }
            @Override public void onBackInvoked() { close(); }
        };
    }

    private final class DrawerHost extends FrameLayout {
        private final int slop;
        private final int flingSpeed;
        private VelocityTracker velocity;
        private int pointer = -1;
        private float downX;
        private float downY;
        private float startOffset;
        private boolean candidate;
        private boolean dragging;

        DrawerHost() {
            super(ui.activity);
            ViewConfiguration config = ViewConfiguration.get(ui.activity);
            slop = config.getScaledTouchSlop();
            flingSpeed = Math.max(config.getScaledMinimumFlingVelocity(), ui.dp(400));
        }

        int direction() { return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1; }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
            int width = Math.min(ui.dp(320), Math.max(ui.dp(48), w - ui.dp(56)));
            if (params.width != width) { params.width = width; panel.setLayoutParams(params); }
            updateEdgeExclusion();
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            setOffset(offset);
        }

        void updateEdgeExclusion() {
            // 系统最多允许排除 200dp 的侧边返回区；其余边缘仍留给系统返回。
            int edge = ui.dp(48);
            int halfHeight = Math.min(ui.dp(100), getHeight() / 2);
            int middle = getHeight() / 2;
            Rect rect = direction() == 1 ? new Rect(0, middle - halfHeight, edge, middle + halfHeight)
                    : new Rect(getWidth() - edge, middle - halfHeight, getWidth(), middle + halfHeight);
            setSystemGestureExclusionRects(visible ? Collections.emptyList() : Collections.singletonList(rect));
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (velocity != null) { velocity.recycle(); }
                velocity = VelocityTracker.obtain();
            }
            if (velocity != null) { velocity.addMovement(event); }
            boolean handled = super.dispatchTouchEvent(event);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (!dragging && animator == null && offset > 0f && offset < 1f) {
                    settle(offset >= 0.5f);
                }
                releaseVelocity();
                pointer = -1;
                candidate = false;
                dragging = false;
            }
            return handled;
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pointer = event.getPointerId(0);
                downX = event.getX();
                downY = event.getY();
                startOffset = offset;
                float edgeDistance = direction() == 1 ? downX : getWidth() - downX;
                candidate = visible || edgeDistance <= ui.dp(48);
                dragging = false;
                if (candidate) { stopAnimation(); }
            } else if (action == MotionEvent.ACTION_MOVE && candidate) {
                int index = event.findPointerIndex(pointer);
                if (index < 0) { return false; }
                float dx = (event.getX(index) - downX) * direction();
                float dy = event.getY(index) - downY;
                if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) { candidate = false; }
                else if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)
                        && (startOffset > 0f || dx > 0f)) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
            } else if (action == MotionEvent.ACTION_POINTER_UP) { replacePointer(event); }
            return dragging;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!dragging) { return super.onTouchEvent(event); }
            int index = event.findPointerIndex(pointer);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (index >= 0) {
                        setOffset(startOffset + (event.getX(index) - downX) * direction() / panel.getWidth());
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    replacePointer(event);
                    break;
                case MotionEvent.ACTION_UP:
                    if (index >= 0) {
                        setOffset(startOffset + (event.getX(index) - downX) * direction() / panel.getWidth());
                    }
                    float speed = 0f;
                    if (velocity != null) {
                        velocity.computeCurrentVelocity(1000);
                        speed = velocity.getXVelocity(pointer) * direction();
                    }
                    settle(Math.abs(speed) >= flingSpeed ? speed > 0f : offset >= 0.5f);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    settle(startOffset >= 0.5f);
                    break;
                default: break;
            }
            return true;
        }

        void releaseVelocity() {
            if (velocity != null) { velocity.recycle(); velocity = null; }
        }

        private void replacePointer(MotionEvent event) {
            int lifted = event.getActionIndex();
            if (event.getPointerId(lifted) != pointer || event.getPointerCount() < 2) { return; }
            int remaining = lifted == 0 ? 1 : 0;
            pointer = event.getPointerId(remaining);
            downX = event.getX(remaining);
            downY = event.getY(remaining);
            startOffset = offset;
        }
    }

    private LinearLayout bar() {
        LinearLayout row = new LinearLayout(ui.activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(56));
        return row;
    }

    private TextView title(String text) {
        TextView title = ui.text(20);
        title.setText(StationText.translate(text));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setAccessibilityHeading(true);
        return title;
    }

    private ImageButton icon(int resource, String description, View.OnClickListener click) {
        ImageButton button = new ImageButton(ui.activity);
        button.setImageResource(resource);
        button.setColorFilter(ui.ink());
        button.setContentDescription(StationText.translate(description));
        button.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        TypedValue background = new TypedValue();
        ui.activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
        button.setBackgroundResource(background.resourceId);
        button.setOnClickListener(click);
        return button;
    }

    private LinearLayout.LayoutParams square() { return new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); }
    private static LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1f); }
}
