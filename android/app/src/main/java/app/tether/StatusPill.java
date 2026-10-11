package app.tether;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small capsule of glass showing the call state with a coloured, softly glowing dot. */
final class StatusPill extends GlassPanel {
    private final Dot dot;
    private final TextView text;

    StatusPill(Context context) {
        super(context);
        setCornerRadiusDp(-1f);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(context, 14), Ui.dp(context, 9), Ui.dp(context, 16), Ui.dp(context, 9));
        dot = new Dot(context);
        row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(context, 14), Ui.dp(context, 14)));
        text = Ui.text(context, "", 14f, Ui.TEXT_PRIMARY, "sans-serif-medium");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(context, 8);
        row.addView(text, lp);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    void set(String label, int color, boolean pulse) {
        text.setText(label);
        dot.set(color, pulse);
    }

    private static final class Dot extends View {
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = Ui.TEXT_TERTIARY;
        private float pulse = 1f;
        private ValueAnimator animator;

        Dot(Context context) {
            super(context);
        }

        void set(int c, boolean animate) {
            color = c;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            pulse = 1f;
            if (animate && !SceneClock.isReducedMotion(getContext())) {
                animator = ValueAnimator.ofFloat(0.35f, 1f);
                animator.setDuration(900);
                animator.setRepeatMode(ValueAnimator.REVERSE);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.addUpdateListener(a -> {
                    pulse = (float) a.getAnimatedValue();
                    invalidate();
                });
                if (isAttachedToWindow()) animator.start();
            }
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (animator != null) animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animator != null) animator.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy);
            halo.setShader(new RadialGradient(cx, cy, r,
                    new int[]{Ui.withAlpha(color, 0.55f * pulse), Ui.withAlpha(color, 0f)},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, halo);
            core.setColor(Ui.withAlpha(color, 0.35f + 0.65f * pulse));
            c.drawCircle(cx, cy, r * 0.46f, core);
        }
    }
}
