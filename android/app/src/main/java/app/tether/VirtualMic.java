package app.tether;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Captures a chosen input device and hands the audio on as 40 ms frames of base64 16-bit PCM
 * (48 kHz, mono), which the web session plays into the page as its microphone. A web page cannot see
 * the phone's individual inputs, so the choice has to be made here, natively.
 *
 * It uses the plain MIC source, never the voice-communication one, so a Bluetooth headset is not
 * pushed into its call mode. Echo cancellation and noise suppression are applied with the platform
 * effects when asked for and available. The microphone permission must already be granted.
 * Callbacks run on the main thread.
 */
final class VirtualMic {
    interface Listener {
        void onFrame(String base64Pcm);

        void onError(String message);
    }

    private static final int RATE = 48000;
    private static final int FRAME = RATE / 25; // 40 ms

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile int generation; // bumped on every start/stop, so a finished thread never touches a newer capture

    boolean isRunning() {
        return running;
    }

    void start(AudioDeviceInfo device, boolean processing, Listener listener) {
        if (running) return;
        final int gen = ++generation;
        running = true;
        new Thread(() -> capture(gen, device, processing, listener), "virtual-mic").start();
    }

    void stop() {
        generation++;
        running = false;
    }

    private void capture(int gen, AudioDeviceInfo device, boolean processing, Listener listener) {
        AudioRecord record = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 4));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                fail(gen, listener, "could not open the microphone");
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
            record.startRecording();
            short[] samples = new short[FRAME];
            while (gen == generation) {
                int n = record.read(samples, 0, samples.length);
                if (n <= 0) {
                    fail(gen, listener, "the microphone stopped");
                    return;
                }
                ByteBuffer bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
                bytes.asShortBuffer().put(samples, 0, n);
                final String frame = Base64.encodeToString(bytes.array(), Base64.NO_WRAP);
                main.post(() -> {
                    if (gen == generation) listener.onFrame(frame);
                });
            }
        } catch (RuntimeException e) {
            fail(gen, listener, "microphone error: " + e.getMessage());
        } finally {
            if (gen == generation) running = false;
            if (aec != null) aec.release();
            if (ns != null) ns.release();
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
