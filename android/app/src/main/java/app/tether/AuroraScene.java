package app.tether;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * The dark scene behind all glass: a deep base, slow colour fields and a few luminous orbs.
 * Glass has nothing to bend without something to look at, so the orbs are deliberately crisp
 * enough for the lens at a panel's rim to visibly pull them around.
 *
 * One instance is drawn by the full-screen background and again, offset, behind every glass panel,
 * which is what makes the panels read as transparent rather than as a copy of the wallpaper.
 */
final class AuroraScene {
    private static final int BASE_TOP = 0xFF06070C;
    private static final int BASE_BOTTOM = 0xFF0B0E18;

    private static final class Blob {
        final int color;
        final float alpha;
        final float radius;       // fraction of the larger screen side
        final float cx, cy;       // orbit centre, fraction of width/height
        final float ax, ay;       // orbit amplitude, fraction of width/height
        final float periodX, periodY, phase;

        Blob(int color, float alpha, float radius, float cx, float cy, float ax, float ay,
             float periodX, float periodY, float phase) {
            this.color = color;
            this.alpha = alpha;
            this.radius = radius;
            this.cx = cx;
            this.cy = cy;
            this.ax = ax;
            this.ay = ay;
            this.periodX = periodX;
            this.periodY = periodY;
            this.phase = phase;
        }
    }

    private static final Blob[] FIELDS = {
            new Blob(0xFF4B5BFF, 0.50f, 0.85f, 0.15f, 0.18f, 0.12f, 0.06f, 47f, 61f, 0.0f),
            new Blob(0xFF19C6D9, 0.30f, 0.75f, 0.90f, 0.46f, 0.08f, 0.08f, 53f, 43f, 1.7f),
            new Blob(0xFFA24BFF, 0.40f, 0.80f, 0.28f, 0.78f, 0.12f, 0.06f, 59f, 71f, 3.1f),
            new Blob(0xFFFF5E8A, 0.20f, 0.60f, 0.88f, 0.92f, 0.08f, 0.04f, 67f, 49f, 4.4f),
    };

    private static final Blob[] ORBS = {
            new Blob(0xFFB9C4FF, 0.55f, 0.10f, 0.78f, 0.12f, 0.06f, 0.03f, 29f, 37f, 0.4f),
            new Blob(0xFF7DF0FF, 0.40f, 0.06f, 0.20f, 0.36f, 0.05f, 0.04f, 33f, 41f, 2.2f),
            new Blob(0xFFE2B8FF, 0.50f, 0.13f, 0.62f, 0.42f, 0.07f, 0.05f, 41f, 31f, 3.6f),
            new Blob(0xFFFFB0C8, 0.38f, 0.07f, 0.12f, 0.64f, 0.05f, 0.04f, 37f, 45f, 5.0f),
            new Blob(0xFF9FB4FF, 0.46f, 0.11f, 0.82f, 0.72f, 0.06f, 0.05f, 35f, 39f, 1.1f),
            new Blob(0xFF8BF5D0, 0.30f, 0.05f, 0.46f, 0.88f, 0.05f, 0.03f, 43f, 33f, 2.9f),
    };

    private final Paint base = new Paint();
    private final Paint[] fieldPaints = new Paint[FIELDS.length];
    private final Paint[] orbPaints = new Paint[ORBS.length];
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastW, lastH;

    AuroraScene(Context context) {
        for (int i = 0; i < FIELDS.length; i++) fieldPaints[i] = unitGradient(FIELDS[i], false);
        for (int i = 0; i < ORBS.length; i++) orbPaints[i] = unitGradient(ORBS[i], true);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0x14FFFFFF);
        ring.setStrokeWidth(Ui.dpf(context, 1f));
    }

    /** A radial gradient of radius 1 at the origin; drawn by scaling the canvas. */
    private static Paint unitGradient(Blob b, boolean orb) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int solid = Ui.withAlpha(b.color, b.alpha);
        int clear = Ui.withAlpha(b.color, 0f);
        p.setShader(orb
                ? new RadialGradient(0, 0, 1f, new int[]{solid, Ui.withAlpha(b.color, b.alpha * 0.55f), clear},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP)
                : new RadialGradient(0, 0, 1f, new int[]{solid, Ui.withAlpha(b.color, b.alpha * 0.4f), clear},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        return p;
    }

    /** Draws the scene into (0,0,w,h) at animation time {@code t} seconds. */
    void draw(Canvas c, float w, float h, float t) {
        if (w != lastW || h != lastH) {
            base.setShader(new LinearGradient(0, 0, 0, h, BASE_TOP, BASE_BOTTOM, Shader.TileMode.CLAMP));
            lastW = w;
            lastH = h;
        }
        float big = Math.max(w, h);
        c.drawRect(0, 0, w, h, base);
        for (int i = 0; i < FIELDS.length; i++) blob(c, FIELDS[i], fieldPaints[i], w, h, big, t);
        for (int i = 0; i < ORBS.length; i++) blob(c, ORBS[i], orbPaints[i], w, h, big, t);
        // Two faint rings give the lens a thin, high-contrast line to bend.
        float x = w * (0.5f + 0.04f * (float) Math.sin(t / 53f * 6.2832f));
        c.drawCircle(x, h * 0.30f, big * 0.22f, ring);
        c.drawCircle(w - x, h * 0.74f, big * 0.30f, ring);
    }

    private static void blob(Canvas c, Blob b, Paint p, float w, float h, float big, float t) {
        float x = w * (b.cx + b.ax * (float) Math.sin(t / b.periodX * 6.2832f + b.phase));
        float y = h * (b.cy + b.ay * (float) Math.cos(t / b.periodY * 6.2832f + b.phase));
        float r = big * b.radius;
        c.save();
        c.translate(x, y);
        c.scale(r, r);
        c.drawCircle(0, 0, 1f, p);
        c.restore();
    }
}
