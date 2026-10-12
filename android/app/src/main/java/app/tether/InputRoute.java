package app.tether;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/**
 * Makes the system route the microphone from a chosen input. A built-in or wired input needs nothing
 * (the recorder's preferred device is enough), but a Bluetooth headset only delivers its microphone once
 * the system has been told to use it for communication. Best effort: it never throws.
 */
final class InputRoute {
    private InputRoute() {
    }

    private static boolean bluetooth(AudioDeviceInfo d) {
        if (d == null) return false;
        int t = d.getType();
        if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
        return Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET;
    }

    @SuppressWarnings("deprecation")
    static void apply(Context context, AudioDeviceInfo input) {
        if (context == null) return;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (!bluetooth(input)) {
                clear(context);
                return;
            }
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo c : am.getAvailableCommunicationDevices()) {
                    if (c.getType() == input.getType() && c.getAddress().equals(input.getAddress())) {
                        am.setCommunicationDevice(c);
                        return;
                    }
                }
            } else {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
            }
        } catch (RuntimeException ignored) {
            // Routing is a hint; the recorder still tries the preferred device.
        }
    }

    @SuppressWarnings("deprecation")
    static void clear(Context context) {
        if (context == null) return;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice();
            } else {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
            }
        } catch (RuntimeException ignored) {
            // Nothing was routed.
        }
    }
}
