package app.tether;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * A dark "regular" Liquid Glass surface.
 *
 * Layers, bottom to top:
 *  1. backdrop: the scene (plus anything registered as a {@link BackdropSource}) as it looks from
 *     behind this panel, saturated and lightly blurred, with an SDF lens that bends it along the
 *     rim (API 33+; API 31-32 get blur only; older versions the plain scene);
 *  2. sheen: a faint white tint, optional colour tint, the specular rim and the touch glow;
 *  3. whatever children the caller adds.
 *
 * Interactive panels flex like gel under the finger and light up from the touch point.
 */
class GlassPanel extends FrameLayout {
    /** Extra content that should be visible through (and refracted by) the glass. */
    interface BackdropSource {
        /** Draw in window coordinates; the canvas origin is the window origin. */
        void drawBackdrop(Canvas canvas);
    }

    private static final String LENS_SHADER =
            "uniform shader content;\n"
                    + "uniform float2 size;\n"
                    + "uniform float radius;\n"
                    + "uniform float band;\n"
                    + "uniform float strength;\n"
                    + "uniform float aberration;\n"
                    + "float sdRoundRect(float2 p, float2 halfSize, float r) {\n"
                    + "  float2 q = abs(p) - halfSize + float2(r, r);\n"
                    + "  return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;\n"
                    + "}\n"
                    + "half4 main(float2 fragCoord) {\n"
                    + "  float2 halfSize = size * 0.5;\n"
                    + "  float2 p = fragCoord - halfSize;\n"
                    + "  float d = sdRoundRect(p, halfSize, radius);\n"
                    + "  float depth = max(-d, 0.0);\n"
                    + "  float u = clamp(depth / band, 0.0, 1.0);\n"
                    + "  float2 g = float2(\n"
                    + "    sdRoundRect(p + float2(1.0, 0.0), halfSize, radius) - sdRoundRect(p - float2(1.0, 0.0), halfSize, radius),\n"
                    + "    sdRoundRect(p + float2(0.0, 1.0), halfSize, radius) - sdRoundRect(p - float2(0.0, 1.0), halfSize, radius));\n"
                    + "  float2 n = g / max(length(g), 0.0001);\n"
                    + "  float v = 1.0 - u;\n"
                    + "  float slope = v / max(sqrt(max(1.0 - v * v, 0.0)), 0.18);\n"
                    + "  float bend = strength * clamp(slope / 3.0, 0.0, 1.0) * v;\n"
                    + "  float2 dir = n * bend;\n"
                    + "  half4 c = content.eval(fragCoord - dir);\n"
                    + "  half r = content.eval(fragCoord - dir * (1.0 + 0.45 * aberration)).r;\n"
                    + "  half b = content.eval(fragCoord - dir * (1.0 - 0.45 * aberration)).b;\n"
                    + "  return half4(r, c.g, b, c.a);\n"
                    + "}\n";

    private final Backdrop backdrop;
    private final Sheen sheen;
    private final List<BackdropSource> sources = new ArrayList<>();
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::invalidateBackdrop;

    private float cornerPx;          // < 0: capsule
    private boolean interactive;
    private int tintColor;
    private float tintAlpha;
    private float lens = 1f;         // 0 = no refraction (used while materialising)
    private RuntimeShader shader;
    private boolean shaderFailed;
    private ValueAnimator materialise;

    GlassPanel(Context context) {
        super(context);
        backdrop = new Backdrop(context);
        sheen = new Sheen(context);
        addView(backdrop, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(sheen, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setClipChildren(true);
        setClipToOutline(true);
        setCornerRadiusDp(28f);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), effectiveCorner());
            }
        });
    }

    GlassPanel setCornerRadiusDp(float dp) {
        cornerPx = dp < 0 ? -1f : Ui.dpf(getContext(), dp);
        invalidateOutline();
        sheen.invalidate();
        rebuildEffect();
        return this;
    }

    /** A colour tint for meaning (primary action, danger), never decoration. Null clears it. */
    GlassPanel setTint(Integer color, float alpha) {
        tintColor = color == null ? 0 : color;
        tintAlpha = color == null ? 0f : alpha;
        sheen.invalidate();
        return this;
    }

    GlassPanel setInteractive(boolean on) {
        interactive = on;
        setClickable(on);
        return this;
    }

    GlassPanel addBackdropSource(BackdropSource source) {
        sources.add(source);
        return this;
    }

    /** Fade-and-bend in: the lens eases in so the panel appears by modulating how it bends light. */
    void materialise() {
        if (materialise != null) materialise.cancel();
        setAlpha(0f);
        setScaleX(0.9f);
        setScaleY(0.9f);
        lens = 0f;
        rebuildEffect();
        materialise = ValueAnimator.ofFloat(0f, 1f);
        materialise.setDuration(520);
        materialise.setInterpolator(new OvershootInterpolator(1.2f));
        materialise.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            lens = Math.max(0f, f);
            setAlpha(Math.min(1f, f * 1.6f));
            float s = 0.9f + 0.1f * f;
            setScaleX(s);
            setScaleY(s);
            rebuildEffect();
        });
        materialise.start();
    }

    private float effectiveCorner() {
        float half = Math.min(getWidth(), getHeight()) / 2f;
        return cornerPx < 0 ? half : Math.min(cornerPx, half);
    }

    void invalidateBackdrop() {
        backdrop.invalidate();
    }

    // ---- lifecycle -------------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SceneClock.get().register(backdrop);
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        SceneClock.get().unregister(backdrop);
        getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        if (materialise != null) materialise.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidateOutline();
        rebuildEffect();
        sheen.invalidate();
    }

    // ---- refraction ------------------------------------------------------------------------

    private void rebuildEffect() {
        if (Build.VERSION.SDK_INT < 31 || getWidth() == 0 || getHeight() == 0) return;
        float density = getResources().getDisplayMetrics().density;
        float blur = 5f * density;
        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(1.45f);
        RenderEffect effect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP);
        effect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturation), effect);
        if (Build.VERSION.SDK_INT >= 33 && !shaderFailed) {
            try {
                if (shader == null) shader = new RuntimeShader(LENS_SHADER);
                float shortSide = Math.min(getWidth(), getHeight());
                // A thin pill only has room for a thin band; big cards get the full 24dp lens.
                float band = Math.min(24f * density, shortSide * 0.45f);
                shader.setFloatUniform("size", getWidth(), getHeight());
                shader.setFloatUniform("radius", effectiveCorner());
                shader.setFloatUniform("band", band);
                shader.setFloatUniform("strength", band * 1.1f * lens);
                shader.setFloatUniform("aberration", 0.35f);
                effect = RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(shader, "content"), effect);
            } catch (RuntimeException e) {
                shaderFailed = true; // keep the blur-only glass rather than crash
                shader = null;
            }
        }
        backdrop.setRenderEffect(effect);
    }

    // ---- touch: gel flex and illumination --------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animate().cancel();
                animate().scaleX(0.95f).scaleY(0.95f).setDuration(120)
                        .setInterpolator(new DecelerateInterpolator()).start();
                sheen.pressAt(e.getX(), e.getY());
                setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                sheen.moveTo(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_UP:
                release();
                if (isPressed() && e.getX() >= 0 && e.getY() >= 0
                        && e.getX() <= getWidth() && e.getY() <= getHeight()) {
                    setPressed(false);
                    performClick();
                }
                setPressed(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                release();
                setPressed(false);
                return true;
            default:
                return true;
        }
    }

    private void release() {
        animate().cancel();
        animate().scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(new OvershootInterpolator(3.2f)).start();
        sheen.releaseGlow();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    // ---- layers ----------------------------------------------------------------------------

    /** Layer 1: the scene as seen from behind this panel. The lens is applied to it as a RenderEffect. */
    private final class Backdrop extends View {
        private final int[] loc = new int[2];

        Backdrop(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            getLocationInWindow(loc);
            int w = getWidth();
            int h = getHeight();
            canvas.save();
            canvas.clipRect(0, 0, w, h);
            // From here on the canvas origin is the window origin.
            canvas.translate(-loc[0], -loc[1]);
            canvas.save();
            canvas.translate(AuroraBackground.originX(), AuroraBackground.originY());
            float sw = AuroraBackground.width() > 0 ? AuroraBackground.width() : getRootView().getWidth();
            float sh = AuroraBackground.height() > 0 ? AuroraBackground.height() : getRootView().getHeight();
            AuroraBackground.scene(getContext()).draw(canvas, sw, sh, SceneClock.get().time());
            canvas.restore();
            for (int i = 0; i < sources.size(); i++) sources.get(i).drawBackdrop(canvas);
            canvas.restore();
        }
    }

    /** Layer 2: tint, specular rim and touch glow. */
    private final class Sheen extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bevel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float glowX, glowY, glowAlpha;
        private ValueAnimator glowAnim;

        Sheen(Context context) {
            super(context);
            rim.setStyle(Paint.Style.STROKE);
            bevel.setStyle(Paint.Style.STROKE);
        }

        void pressAt(float x, float y) {
            glowX = x;
            glowY = y;
            animateGlow(1f, 140);
        }

        void moveTo(float x, float y) {
            glowX = x;
            glowY = y;
            invalidate();
        }

        void releaseGlow() {
            animateGlow(0f, 520);
        }

        private void animateGlow(float to, long ms) {
            if (glowAnim != null) glowAnim.cancel();
            glowAnim = ValueAnimator.ofFloat(glowAlpha, to);
            glowAnim.setDuration(ms);
            glowAnim.addUpdateListener(a -> {
                glowAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            glowAnim.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;
            float density = getResources().getDisplayMetrics().density;
            float r = effectiveCorner();

            // Tint: a faint white lift with a brighter top, like light entering from above.
            fill.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x26FFFFFF, 0x0DFFFFFF, 0x1AFFFFFF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(0, 0, w, h, r, r, fill);
            if (tintAlpha > 0f) {
                fill.setShader(null);
                fill.setColor(Ui.withAlpha(tintColor, tintAlpha));
                canvas.drawRoundRect(0, 0, w, h, r, r, fill);
            }

            // Specular rim: lit at the top-left and bottom-right where the "light" catches the
            // bevel, nearly dark between. Two strokes give the edge some thickness.
            float s1 = 1.3f * density;
            rect.set(s1 / 2f, s1 / 2f, w - s1 / 2f, h - s1 / 2f);
            rim.setStrokeWidth(s1);
            rim.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xCCFFFFFF, 0x2EFFFFFF, 0x0DFFFFFF, 0x2EFFFFFF, 0x80FFFFFF},
                    new float[]{0f, 0.22f, 0.5f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, Math.max(r - s1 / 2f, 0f), Math.max(r - s1 / 2f, 0f), rim);

            float inset = 2.4f * density;
            rect.set(inset, inset, w - inset, h - inset);
            bevel.setStrokeWidth(1f * density);
            bevel.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0x4DFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x33FFFFFF},
                    new float[]{0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, Math.max(r - inset, 0f), Math.max(r - inset, 0f), bevel);

            // Illuminates from within, starting under the fingertip.
            if (glowAlpha > 0.001f) {
                float gr = Math.max(w, h) * 0.9f;
                glow.setShader(new RadialGradient(glowX, glowY, gr,
                        new int[]{Ui.withAlpha(Color.WHITE, 0.30f * glowAlpha), Ui.withAlpha(Color.WHITE, 0f)},
                        null, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(0, 0, w, h, r, r, glow);
            }
        }
    }
}
