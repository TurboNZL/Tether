package app.tether;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/** Full-window view that paints the {@link AuroraScene} and tells glass panels where it sits. */
final class AuroraBackground extends View {
    private static AuroraScene scene;
    private static final int[] origin = new int[2];
    private static float sceneW, sceneH;

    private final int[] loc = new int[2];

    AuroraBackground(Context context) {
        super(context);
        if (scene == null) scene = new AuroraScene(context.getApplicationContext());
    }

    static AuroraScene scene(Context context) {
        if (scene == null) scene = new AuroraScene(context.getApplicationContext());
        return scene;
    }

    /** Window-space position and size of the scene, as of the last time the background drew. */
    static int originX() {
        return origin[0];
    }

    static int originY() {
        return origin[1];
    }

    static float width() {
        return sceneW;
    }

    static float height() {
        return sceneH;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SceneClock.get().register(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        SceneClock.get().unregister(this);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        getLocationInWindow(loc);
        origin[0] = loc[0];
        origin[1] = loc[1];
        sceneW = getWidth();
        sceneH = getHeight();
        scene.draw(canvas, sceneW, sceneH, SceneClock.get().time());
    }
}
