package app.tether;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small hand-drawn icons (no resources needed), drawn as rounded strokes like SF Symbols. */
final class GlyphView extends View {
    static final int MIC = 0;
    static final int MIC_OFF = 1;
    static final int SHARE = 2;
    static final int END = 3;
    static final int PLAY = 4;
    static final int CHEVRON_DOWN = 5;
    static final int CHECK = 6;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private int glyph;

    GlyphView(Context context, int glyph, int color) {
        super(context);
        this.glyph = glyph;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    void setGlyph(int glyph) {
        if (this.glyph != glyph) {
            this.glyph = glyph;
            invalidate();
        }
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        if (s <= 0) return;
        float u = s / 24f; // draw on a 24-unit grid
        c.save();
        c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
        c.scale(u, u);
        paint.setStrokeWidth(1.9f);
        paint.setStyle(Paint.Style.STROKE);
        switch (glyph) {
            case MIC:
            case MIC_OFF:
                mic(c, glyph == MIC_OFF);
                break;
            case SHARE:
                path.reset();
                path.moveTo(12, 15);
                path.lineTo(12, 3.5f);
                path.moveTo(7.5f, 8f);
                path.lineTo(12, 3.5f);
                path.lineTo(16.5f, 8f);
                c.drawPath(path, paint);
                path.reset();
                path.moveTo(8f, 11f);
                path.lineTo(6.5f, 11f);
                path.quadTo(5f, 11f, 5f, 12.5f);
                path.lineTo(5f, 18.5f);
                path.quadTo(5f, 20f, 6.5f, 20f);
                path.lineTo(17.5f, 20f);
                path.quadTo(19f, 20f, 19f, 18.5f);
                path.lineTo(19f, 12.5f);
                path.quadTo(19f, 11f, 17.5f, 11f);
                path.lineTo(16f, 11f);
                c.drawPath(path, paint);
                break;
            case END:
                // A handset laid flat, the universal "hang up".
                path.reset();
                path.moveTo(3f, 14.5f);
                path.quadTo(3f, 12.6f, 5f, 11.6f);
                path.quadTo(12f, 8.4f, 19f, 11.6f);
                path.quadTo(21f, 12.6f, 21f, 14.5f);
                path.lineTo(21f, 15.2f);
                path.quadTo(21f, 16.2f, 20f, 16.2f);
                path.lineTo(17.2f, 16.2f);
                path.quadTo(16f, 16.2f, 16f, 15f);
                path.lineTo(16f, 14f);
                path.quadTo(12f, 12.7f, 8f, 14f);
                path.lineTo(8f, 15f);
                path.quadTo(8f, 16.2f, 6.8f, 16.2f);
                path.lineTo(4f, 16.2f);
                path.quadTo(3f, 16.2f, 3f, 15.2f);
                path.close();
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(1.2f);
                c.drawPath(path, paint);
                break;
            case PLAY:
                path.reset();
                path.moveTo(8f, 5.5f);
                path.lineTo(18.5f, 12f);
                path.lineTo(8f, 18.5f);
                path.close();
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(1.6f);
                c.drawPath(path, paint);
                break;
            case CHEVRON_DOWN:
                path.reset();
                path.moveTo(6f, 9.5f);
                path.lineTo(12f, 15.5f);
                path.lineTo(18f, 9.5f);
                c.drawPath(path, paint);
                break;
            case CHECK:
                path.reset();
                path.moveTo(5f, 12.5f);
                path.lineTo(10f, 17.5f);
                path.lineTo(19f, 7f);
                c.drawPath(path, paint);
                break;
            default:
                break;
        }
        c.restore();
    }

    private void mic(Canvas c, boolean off) {
        rect.set(8.5f, 2.8f, 15.5f, 14.8f);
        c.drawRoundRect(rect, 3.5f, 3.5f, paint);
        path.reset();
        path.moveTo(5.5f, 11.5f);
        path.quadTo(5.5f, 17.5f, 12f, 17.5f);
        path.quadTo(18.5f, 17.5f, 18.5f, 11.5f);
        c.drawPath(path, paint);
        c.drawLine(12f, 17.5f, 12f, 21f, paint);
        c.drawLine(8.5f, 21f, 15.5f, 21f, paint);
        if (off) {
            paint.setStrokeWidth(2.1f);
            c.drawLine(4f, 3.5f, 20f, 20.5f, paint);
        }
    }
}
