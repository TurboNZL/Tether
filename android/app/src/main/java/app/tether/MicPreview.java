package app.tether;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;

/**
 * "Hear yourself": records a few seconds from a chosen input with the same settings the web session's
 * native microphone uses (plain MIC source, optional echo cancellation / noise suppression, the chosen
 * microphone volume), plays it back through the phone's media output, and rates the peak level.
 *
 * It reads the microphone by itself, so it works whether or not the call is muted. The microphone
 * permission must already be granted. Callbacks run on the main thread.
 */
final class MicPreview {
    interface Listener {
        /** state: "recording", "playing", "done" or "error"; peak is linear 0.. (above 1 means clipping). */
        void onState(String state, float peak, String verdict, String message);
    }

    private static final int RATE = 48000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int generation;
    private volatile boolean running;

    boolean isRunning() {
        return running;
    }

    void cancel() {
        generation++;
        running = false;
    }

    /** Rating of a linear peak: a normal voice peaks around -12 to -6 dB. */
    static String rate(float peak) {
        double db = peak > 0 ? 20.0 * Math.log10(peak) : -120.0;
        if (db >= -3) return "clipping";
        if (db >= -21) return "good";
        if (db >= -32) return "quiet";
        return "silent";
    }

    void start(AudioDeviceInfo device, boolean processing, float gain, int seconds, Listener listener) {
        if (running) return;
        final int gen = ++generation;
        running = true;
        new Thread(() -> run(gen, device, processing, gain, seconds, listener), "mic-preview").start();
    }

    private void post(int gen, Listener l, String state, float peak, String verdict, String message) {
        main.post(() -> {
            if (gen == generation) l.onState(state, peak, verdict, message);
        });
    }

    private void run(int gen, AudioDeviceInfo device, boolean processing, float gain, int seconds, Listener l) {
        AudioRecord record = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        AudioTrack track = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 2));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                post(gen, l, "error", 0, "", "could not open the microphone");
                return;
            }
            if (device != null) record.setPreferredDevice(device);
            if (processing) {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.getAudioSessionId());
                    if (aec != null) aec.setEnabled(true);
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(record.getAudioSessionId());
                    if (ns != null) ns.setEnabled(true);
                }
            }
            post(gen, l, "recording", 0, "", "");
            record.startRecording();
            short[] raw = new short[RATE * seconds];
            int got = 0;
            while (got < raw.length && gen == generation) {
                int n = record.read(raw, got, Math.min(RATE / 10, raw.length - got));
                if (n <= 0) {
                    post(gen, l, "error", 0, "", "the microphone stopped");
                    return;
                }
                got += n;
            }
            if (gen != generation) return;
            record.stop();

            // Apply the microphone volume the way the call does, and measure what would be sent.
            short[] out = new short[got];
            float peak = 0f;
            for (int i = 0; i < got; i++) {
                float v = raw[i] / 32768f * gain;
                peak = Math.max(peak, Math.abs(v));
                out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32768f)));
            }
            String verdict = rate(peak);
            post(gen, l, "playing", peak, verdict, "");

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(Math.max(2, got * 2))
                    .build();
            track.write(out, 0, got);
            track.play();
            long until = System.currentTimeMillis() + got * 1000L / RATE + 300;
            while (System.currentTimeMillis() < until && gen == generation) Thread.sleep(50);
            post(gen, l, "done", peak, verdict, "");
        } catch (RuntimeException e) {
            post(gen, l, "error", 0, "", "microphone error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (gen == generation) running = false;
            if (aec != null) aec.release();
            if (ns != null) ns.release();
            if (record != null) {
                try {
                    record.stop();
                } catch (RuntimeException ignored) {
                    // Already stopped.
                }
                record.release();
            }
            if (track != null) {
                try {
                    track.stop();
                } catch (RuntimeException ignored) {
                    // Already stopped.
                }
                track.release();
            }
        }
    }
}
