package com.malbers.eversignal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * "Stay in front": bring EverSignal back after the player takes the screen.
 *
 * Measured on the DMP-A6 (2026-11-15, tested with a separate stats app): the player takes the screen when a
 * track is STARTED from the phone app or AirPlay connects; it came back in ~2 s every time, playback
 * uninterrupted. Returns only when the app was hidden within 4 s of a playback start, so Home and
 * Settings are left alone. The VU button suspends it until the app is opened again.
 * Uses the Poller's start time (the Poller keeps running while the app is in the background).
 */
public final class KeepOnTopService extends Service implements Runnable {

    static volatile boolean activityVisible = false;
    static volatile long hiddenAt = 0;
    /** Set by the VU button: the player's own VU screen is wanted, don't pull the app back over it. */
    static volatile boolean suspended = false;

    private static final String CHANNEL = "stay_in_front";
    private static final long WINDOW_MS = 4000, RETURN_DELAY_MS = 500;   // 1.0.10: 1500 -> 500 (Michael: faster)

    private volatile boolean running;
    private Thread thread;
    private long returnedForHide = -1;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Stay in front", NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(1, new Notification.Builder(this, CHANNEL)
            .setContentTitle("EverSignal").setContentText("Returns to the front after the player takes over")
            .setSmallIcon(android.R.drawable.ic_media_play).build());
        if (thread == null) {
            running = true;
            thread = new Thread(this, "stay-in-front");
            thread.setDaemon(true);
            thread.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void run() {
        while (running) {
            long now = System.currentTimeMillis();
            long hid = hiddenAt;
            if (!activityVisible && !suspended && Poller.alive
                    && Math.abs(Poller.lastStartAt - hid) <= WINDOW_MS
                    && returnedForHide != hid && now - hid >= RETURN_DELAY_MS) {
                returnedForHide = hid;
                try {
                    startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                } catch (Exception ignored) { }
            }
            try { Thread.sleep(300); } catch (InterruptedException e) { return; }
        }
    }
}
