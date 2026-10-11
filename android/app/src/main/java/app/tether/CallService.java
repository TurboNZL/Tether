package app.tether;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the call alive for as long as it runs: a microphone foreground service (so Android lets
 * the process keep capturing with the screen off or the app closed), a partial wake lock and a
 * Wi-Fi lock (so the CPU and radio do not nap), and a notification with Mute / Hang up so the
 * call can be controlled without opening the app.
 */
public class CallService extends Service {
    static final String ACTION_TOGGLE_MUTE = "app.tether.TOGGLE_MUTE";
    static final String ACTION_HANGUP = "app.tether.HANGUP";

    private static final String CHANNEL_ID = "call";
    private static final int NOTIFICATION_ID = 1;

    private static CallService instance;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    /** Rebuild the notification after the call state (status or mute) changed. */
    static void refresh() {
        CallService s = instance;
        if (s != null && CallManager.get().isActive()) {
            s.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, s.buildNotification());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tether:call");
        wakeLock.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(
                Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "tether:call");
        wifiLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        CallManager call = CallManager.get();
        if (!call.isActive()) {
            // A stale notification action after the process was recreated: nothing to keep alive.
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_HANGUP.equals(action)) {
            call.hangup("you hung up");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_MUTE.equals(action) && call.isActive()) {
            call.setMuted(!call.isMuted());
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "Call", NotificationManager.IMPORTANCE_LOW));
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
        if (!wifiLock.isHeld()) wifiLock.acquire();
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        CallManager call = CallManager.get();
        boolean muted = call.isMuted();
        long left = call.getHangupRemainingMs();
        String timerNote = left >= 0 ? " - hangs up in " + CallManager.formatMinutes(left) : "";
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Action muteAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_call),
                muted ? "Unmute" : "Mute",
                servicePending(ACTION_TOGGLE_MUTE, 1)).build();
        Notification.Action hangAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_call),
                "Hang up",
                servicePending(ACTION_HANGUP, 2)).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle(muted ? "Tether - mic muted" : "Tether")
                .setContentText((muted ? "Still connected, " + call.getStatus() : "Call " + call.getStatus()) + timerNote)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_CALL)
                .addAction(muteAction)
                .addAction(hangAction)
                .build();
    }

    private PendingIntent servicePending(String action, int requestCode) {
        Intent i = new Intent(this, CallService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
