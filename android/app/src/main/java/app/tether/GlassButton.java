package app.tether;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A capsule of interactive glass with an icon and a label. */
final class GlassButton extends GlassPanel {
    private final GlyphView glyph;
    private final TextView label;

    GlassButton(Context context, int glyphId, String text) {
        super(context);
        setCornerRadiusDp(-1f);
        setInteractive(true);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0);
        glyph = new GlyphView(context, glyphId, Ui.TEXT_PRIMARY);
        row.addView(glyph, new LinearLayout.LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22)));
        label = Ui.text(context, text, 15f, Ui.TEXT_PRIMARY, "sans-serif-medium");
        label.setSingleLine();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(context, 8);
        row.addView(label, lp);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void set(int glyphId, String text) {
        glyph.setGlyph(glyphId);
        label.setText(text);
        setContentDescription(text);
    }
}
