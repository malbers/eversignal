package com.malbers.eversignal;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** "Session · today": listening time, tracks, format mix, peak rates and bitrate range; kept on the player, reset at midnight. */
public final class Session {
    public long listenedMs, hiresMs, cdMs, dsdMs, lossyMs;
    public int tracks;
    public double maxPcmHz, maxDsdHz, minBps, maxBps;
    private String day, lastCounted;
    private final SharedPreferences prefs;
    private long lastSave = 0;

    public Session(Context ctx) {
        prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE);
        day = prefs.getString("day", today());
        listenedMs = prefs.getLong("listened", 0);
        hiresMs = prefs.getLong("hires", 0);
        cdMs = prefs.getLong("cd", 0);
        dsdMs = prefs.getLong("dsd", 0);
        lossyMs = prefs.getLong("lossy", 0);
        tracks = prefs.getInt("tracks", 0);
        maxPcmHz = d("maxPcm");
        maxDsdHz = d("maxDsd");
        minBps = d("minBps");
        maxBps = d("maxBps");
        lastCounted = prefs.getString("lastCounted", null);
        rollover();
    }

    private double d(String k) { return Double.longBitsToDouble(prefs.getLong(k, 0)); }

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    private void rollover() {
        String dd = today();
        if (dd.equals(day)) return;
        day = dd;
        listenedMs = hiresMs = cdMs = dsdMs = lossyMs = 0;
        tracks = 0;
        maxPcmHz = maxDsdHz = minBps = maxBps = 0;
        lastCounted = null;
        save(true);
    }

    /** Called every poll. A track counts once, the first time it is seen playing (also the one playing at app start). */
    public synchronized void record(Track t, long dtMs) {
        rollover();
        if (t == null || t.state != 3 || t.title == null) return;
        long dd = Math.max(0, Math.min(dtMs, 2000));
        listenedMs += dd;
        switch (t.formatClass()) {
            case "dsd": dsdMs += dd; break;
            case "lossy": lossyMs += dd; break;
            case "hires": hiresMs += dd; break;
            default: cdMs += dd;
        }
        if (!t.key.equals(lastCounted)) { tracks++; lastCounted = t.key; }
        if (t.rateHz != null) {
            if (t.dsd) maxDsdHz = Math.max(maxDsdHz, t.rateHz); else maxPcmHz = Math.max(maxPcmHz, t.rateHz);
        }
        if (t.bitrateBps != null && t.bitrateBps > 0) {
            minBps = minBps <= 0 ? t.bitrateBps : Math.min(minBps, t.bitrateBps);
            maxBps = Math.max(maxBps, t.bitrateBps);
        }
        save(false);
    }

    private void save(boolean now) {
        long t = System.currentTimeMillis();
        if (!now && t - lastSave < 15000) return;
        lastSave = t;
        prefs.edit().putString("day", day).putLong("listened", listenedMs).putLong("hires", hiresMs).putLong("cd", cdMs)
            .putLong("dsd", dsdMs).putLong("lossy", lossyMs).putInt("tracks", tracks).putString("lastCounted", lastCounted)
            .putLong("maxPcm", Double.doubleToLongBits(maxPcmHz)).putLong("maxDsd", Double.doubleToLongBits(maxDsdHz))
            .putLong("minBps", Double.doubleToLongBits(minBps)).putLong("maxBps", Double.doubleToLongBits(maxBps)).apply();
    }

    public synchronized void flush() { save(true); }

    /** 1.0.10: clear today's stats now (they also clear on their own at midnight). */
    public synchronized void reset() {
        listenedMs = hiresMs = cdMs = dsdMs = lossyMs = 0;
        tracks = 0;
        maxPcmHz = maxDsdHz = minBps = maxBps = 0;
        lastCounted = null;
        save(true);
    }
}
