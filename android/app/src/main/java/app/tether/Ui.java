package app.tether;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.widget.TextView;

/** Shared sizes, colours and text styles for the glass UI. */
final class Ui {
    static final int TEXT_PRIMARY = 0xF2FFFFFF;
    static final int TEXT_SECONDARY = 0xA6FFFFFF;
    static final int TEXT_TERTIARY = 0x80FFFFFF;

    static final int GREEN = 0xFF34D399;
    static final int AMBER = 0xFFFBBF24;
    static final int RED = 0xFFFF453A;
    static final int BLUE = 0xFF5B8CFF;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static float dpf(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** The system UI font at a given weight (400 regular, 500 medium, 600 semibold). */
    static Typeface font(int weight) {
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.DEFAULT, weight, false);
        return weight >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }

    /** {@code family}: "monospace", "sans-serif-medium", "semibold"; anything else is regular. */
    static TextView text(Context c, CharSequence s, float sp, int color, String family) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if ("monospace".equals(family)) {
            t.setTypeface(Typeface.MONOSPACE);
        } else if ("semibold".equals(family)) {
            t.setTypeface(font(600));
        } else if ("sans-serif-medium".equals(family)) {
            t.setTypeface(font(500));
        } else {
            t.setTypeface(font(400));
        }
        t.setIncludeFontPadding(false);
        return t;
    }

    static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
