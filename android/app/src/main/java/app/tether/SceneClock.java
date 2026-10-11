package app.tether;

import android.content.Context;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Single animation clock for everything that draws the {@link AuroraScene}. It only runs while at
 * least one view is attached, ticks at about 30 fps (the scene drifts slowly), and stays still when
 * the user turned animations off.
 */
final class SceneClock implements Choreographer.FrameCallback {
    private static final long FRAME_NANOS = 33_000_000L;

    private static SceneClock instance;

    static SceneClock get() {
        if (instance == null) instance = new SceneClock();
        return instance;
    }

    private final List<View> views = new ArrayList<>();
    private boolean running;
    private long lastTick;
    private float seconds = 12f; // start part-way through so the first frame is not symmetric
    private boolean reducedMotion;

    float time() {
        return seconds;
    }

    void register(View v) {
        if (!views.contains(v)) views.add(v);
        reducedMotion = Settings.Global.getFloat(
                v.getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        if (!running && !reducedMotion) {
            running = true;
            lastTick = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    void unregister(View v) {
        views.remove(v);
        if (views.isEmpty() && running) {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (lastTick == 0) lastTick = frameTimeNanos;
        if (frameTimeNanos - lastTick >= FRAME_NANOS) {
            seconds += (frameTimeNanos - lastTick) / 1_000_000_000f;
            lastTick = frameTimeNanos;
            for (int i = 0; i < views.size(); i++) views.get(i).invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    static boolean isReducedMotion(Context c) {
        return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }
}
