package app.tether;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

/**
 * Slider with a flat translucent track and a glass lens for a thumb. Like the system control, the
 * thumb swells while held so the track can be seen bending through it.
 */
final class GlassSlider extends FrameLayout implements GlassPanel.BackdropSource {
    interface Listener {
        void onValueChanged(float value, boolean fromUser);
    }

    private final GlassPanel thumb;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];
    private final float trackH;
    private final float thumbW;
    private final float thumbH;
    private final float max;
    private final float tick;
    private float value;
    private boolean dragging;
    private Listener listener;

    GlassSlider(Context context, float max, float tick) {
        super(context);
        this.max = max;
        this.tick = tick;
        trackH = Ui.dpf(context, 8f);
        thumbW = Ui.dpf(context, 46f);
        thumbH = Ui.dpf(context, 30f);
        setWillNotDraw(false);
        setClipChildren(false);
        setFocusable(true);

        trackPaint.setColor(0x2EFFFFFF);
        tickPaint.setColor(0x66FFFFFF);
        tickPaint.setStrokeWidth(Ui.dpf(context, 1.5f));
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        thumb = new GlassPanel(context);
        thumb.setCornerRadiusDp(-1f);
        thumb.addBackdropSource(this);
        addView(thumb, new LayoutParams((int) thumbW, (int) thumbH));
    }

    void setListener(Listener l) {
        listener = l;
    }

    float getValue() {
        return value;
    }

    void setValue(float v) {
        setValueInternal(v, false);
    }

    private void setValueInternal(float v, boolean fromUser) {
        float clamped = Math.max(0f, Math.min(max, v));
        if (clamped == value && !fromUser) return;
        value = clamped;
        positionThumb();
        invalidate();
        thumb.invalidateBackdrop();
        if (listener != null) listener.onValueChanged(value, fromUser);
    }

    @Override
    protected int getSuggestedMinimumHeight() {
        return (int) (thumbH + Ui.dpf(getContext(), 14f));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = Math.max((int) (thumbH + Ui.dpf(getContext(), 14f)), getSuggestedMinimumHeight());
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        positionThumb();
    }

    private float trackLeft() {
        return thumbW / 2f;
    }

    private float trackRight() {
        return getWidth() - thumbW / 2f;
    }

    private void positionThumb() {
        if (getWidth() == 0) return;
        float x = trackLeft() + (trackRight() - trackLeft()) * (value / max);
        thumb.setTranslationX(x - thumbW / 2f);
        thumb.setTranslationY((getHeight() - thumbH) / 2f);
    }

    private void drawTrack(Canvas c) {
        float cy = getHeight() / 2f;
        float left = trackLeft();
        float right = trackRight();
        float x = left + (right - left) * (value / max);
        float r = trackH / 2f;
        rect.set(left - r, cy - r, right + r, cy + r);
        c.drawRoundRect(rect, r, r, trackPaint);

        fillPaint.setShader(new LinearGradient(left, 0, right, 0, 0x99BFD0FF, 0xE6FFFFFF, Shader.TileMode.CLAMP));
        rect.set(left - r, cy - r, Math.max(x, left), cy + r);
        c.drawRoundRect(rect, r, r, fillPaint);

        if (tick > 0f && tick < max) {
            float tx = left + (right - left) * (tick / max);
            c.drawLine(tx, cy - trackH * 1.3f, tx, cy - trackH * 0.7f, tickPaint);
            c.drawLine(tx, cy + trackH * 0.7f, tx, cy + trackH * 1.3f, tickPaint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawTrack(canvas);
    }

    /** The glass thumb sees the track (and the scene) through its lens. */
    @Override
    public void drawBackdrop(Canvas canvas) {
        getLocationInWindow(loc);
        canvas.save();
        canvas.translate(loc[0], loc[1]);
        drawTrack(canvas);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                swell(true);
                setValueInternal(valueAt(e.getX()), true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) setValueInternal(valueAt(e.getX()), true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                swell(false);
                if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private float valueAt(float x) {
        float f = (x - trackLeft()) / (trackRight() - trackLeft());
        float v = Math.max(0f, Math.min(1f, f)) * max;
        // Snap to the 100% mark when close, so "unity" is easy to find by feel.
        if (tick > 0f && Math.abs(v - tick) < max * 0.025f) v = tick;
        return v;
    }

    private void swell(boolean on) {
        thumb.animate().cancel();
        thumb.animate().scaleX(on ? 1.28f : 1f).scaleY(on ? 1.28f : 1f)
                .setDuration(on ? 140 : 380)
                .setInterpolator(on ? new DecelerateInterpolator() : new OvershootInterpolator(2.4f))
                .start();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, max, value));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            setValueInternal(value + max * 0.05f, true);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            setValueInternal(value - max * 0.05f, true);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
