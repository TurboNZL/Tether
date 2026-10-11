package app.tether;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * A horizontal microphone level bar: fast attack, slower release, with a short peak hold marker.
 * The scale is dBFS (-60 dB empty, 0 dB full), which is how loud a voice actually looks.
 */
final class LevelMeterView extends View {
    private static final long PEAK_HOLD_MS = 900;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float level;
    private float peak;
    private long peakAt;

    LevelMeterView(Context context) {
        super(context);
        track.setColor(0x2EFFFFFF);
        peakPaint.setColor(0xD9FFFFFF);
    }

    /** Linear amplitude (RMS, 0..1) to a 0..1 bar position. */
    static float toLevel(float rms) {
        if (rms <= 0.0001f) return 0f;
        double db = 20.0 * Math.log10(rms);
        return (float) Math.max(0.0, Math.min(1.0, (db + 60.0) / 60.0));
    }

    void setRms(float rms) {
        setLevel(toLevel(rms));
    }

    void setLevel(float next) {
        level = next > level ? next : level * 0.72f + next * 0.28f;
        long now = SystemClock.uptimeMillis();
        if (next >= peak || now - peakAt > PEAK_HOLD_MS) {
            peak = next;
            peakAt = now;
        }
        invalidate();
    }

    void reset() {
        level = 0f;
        peak = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, track);
        if (level > 0.01f) {
            fill.setColor(level > 0.92f ? Ui.RED : level > 0.75f ? Ui.AMBER : Ui.GREEN);
            rect.set(0, 0, Math.max(h, w * level), h);
            canvas.drawRoundRect(rect, r, r, fill);
        }
        if (peak > 0.02f) {
            float x = Math.max(h, w * peak);
            rect.set(x - h / 3f, 0, x, h);
            canvas.drawRoundRect(rect, r / 2f, r / 2f, peakPaint);
        }
    }
}
