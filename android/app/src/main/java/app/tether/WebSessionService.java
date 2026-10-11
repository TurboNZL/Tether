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
 * Keeps an embedded web session alive while the screen is off or the app is in the background:
 * a microphone foreground service (so Android lets the process keep capturing), a partial wake lock
 * and a Wi-Fi lock, and a notification with a Close page action and the hang-up timer countdown.
 */
public class WebSessionService extends Service {
    static final String ACTION_CLOSE = "app.tether.CLOSE_PAGE";

    private static final String CHANNEL_ID = "web";
    private static final int NOTIFICATION_ID = 2;

    private static WebSessionService instance;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    /** Rebuild the notification after the timer changed. */
    static void refresh() {
        WebSessionService s = instance;
        if (s != null && WebSession.get().isActive()) {
            s.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, s.buildNotification());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tether:web");
        wakeLock.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(
                Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "tether:web");
        wifiLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        WebSession session = WebSession.get();
        if (!session.isActive()) {
            // A stale notification action after the process was recreated: nothing to keep alive.
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CLOSE.equals(action)) {
            session.close("closed from the notification");
            stopSelf();
            return START_NOT_STICKY;
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "Web session", NotificationManager.IMPORTANCE_LOW));
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
        long left = WebSession.get().getRemainingMs();
        Intent openIntent = new Intent(this, WebSessionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Action close = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_call), "Close page",
                PendingIntent.getService(this, 3,
                        new Intent(this, WebSessionService.class).setAction(ACTION_CLOSE),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle("Tether - web session")
                .setContentText(left >= 0 ? "Closes in " + WebSession.formatMinutes(left) : "Page open, no timer")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_CALL)
                .addAction(close)
                .build();
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
