package com.malbers.eversignal;

/**
 * 1.0.15: Full-Screen Album Mode goes truly full screen (Michael 2026-11-25: "Just want to see album art"; "when you
 * carousel it comes back till next auto rotate"). The reduced stats line, the picture dots/count and the LYRICS
 * button (the "chrome") show when the viewer opens and on a new track, fade out when the slideshow next flips on its
 * own (or, for a single picture, when it would have), and come back on a manual step (swipe, remote, wheel) until the
 * next auto flip. Lyrics keep the line. Pure Java, no Android.
 */
final class ViewerChrome {
    static final long FADE_MS = 600;

    private boolean shown = true;
    private long changedAt;          // 0 = long ago: fully shown, no fade-in on open
    private String trackKey;

    /** Viewer opened (or reloaded for a new album): shown at once. */
    void reset(String key) { shown = true; changedAt = 0; trackKey = key; }

    /** Manual step: back until the next auto flip. */
    void show(long now) { if (!shown) { shown = true; changedAt = now; } }

    /** The slideshow flipped (or would have) on its own. */
    void hide(long now) { if (shown) { shown = false; changedAt = now; } }

    /** Called every frame with the playing track: a new track shows the line again. */
    void track(String key, long now) {
        if (key == null || key.equals(trackKey)) return;
        if (trackKey != null) show(now);
        trackKey = key;
    }

    boolean shown() { return shown; }

    /** 0..1, fading in or out over FADE_MS. */
    float alpha(long now) {
        float k = Math.max(0f, Math.min(1f, (now - changedAt) / (float) FADE_MS));
        return shown ? k : 1f - k;
    }

    boolean fading(long now) { return now - changedAt < FADE_MS; }
}
