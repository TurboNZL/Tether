package app.tether;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

/**
 * Reads a chosen microphone and reports its level ten times a second, so the user can see that the
 * mic works. Capture uses the same plain MIC source as a call, so it shows what a call would send.
 * The microphone permission must already be granted. Callbacks run on the main thread.
 */
final class MicTester {
    interface Listener {
        void onLevel(float rms);

        void onError(String message);
    }

    private static final int RATE = 48000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile int generation; // bumped on every start/stop, so a finished thread never touches a newer test

    boolean isRunning() {
        return running;
    }

    void start(AudioDeviceInfo device, Listener listener) {
        if (running) return;
        final int gen = ++generation;
        running = true;
        new Thread(() -> capture(gen, device, listener), "mic-test").start();
    }

    void stop() {
        generation++;
        running = false;
    }

    private void capture(int gen, AudioDeviceInfo device, Listener listener) {
        AudioRecord record = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 5 * 2));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                fail(gen, listener, "could not open the microphone");
                return;
            }
            if (device != null) record.setPreferredDevice(device);
            record.startRecording();
            short[] buf = new short[RATE / 10]; // 100 ms
            while (gen == generation) {
                int n = record.read(buf, 0, buf.length);
                if (n <= 0) {
                    fail(gen, listener, "the microphone stopped");
                    return;
                }
                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                final float rms = (float) (Math.sqrt(sum / n) / 32768.0);
                main.post(() -> {
                    if (gen == generation) listener.onLevel(rms);
                });
            }
        } catch (RuntimeException e) {
            fail(gen, listener, "microphone error: " + e.getMessage());
        } finally {
            if (gen == generation) running = false;
            if (record != null) {
                try {
                    record.stop();
                } catch (RuntimeException ignored) {
                    // Never started.
                }
                record.release();
            }
        }
    }

    private void fail(int gen, Listener listener, String message) {
        main.post(() -> {
            if (gen != generation) return;
            running = false;
            listener.onError(message);
        });
    }
}
