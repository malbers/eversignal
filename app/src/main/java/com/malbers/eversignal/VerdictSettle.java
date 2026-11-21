package com.malbers.eversignal;

/**
 * 1.0.6: the badge only changes once the new verdict has been steady for STEADY_MS. Switching EQ or starting an
 * MQA file makes the A6 pass through in-between outputs for a second or two (seen 2026-11-16: Bit-perfect, then
 * MQA ×8, then Resampled, then EQ active), and each of those would otherwise flash as a verdict. Until the first
 * steady verdict for a track (START_MS, which covers the A6's MQA detection) no badge is shown. Paused shows at once.
 * Runs after OutputHold and never changes what it holds; it only decides when the badge may change.
 */
final class VerdictSettle {
    static final long STEADY_MS = 2000, START_MS = 3000;

    private String key;
    private Track.Badge shown, pending;
    private String shownText, shownNote, pendingText;
    private boolean shownMqa;
    private long pendingSince;

    Track apply(Track t, long now) {
        if (key == null || !key.equals(t.key)) { key = t.key; shown = null; pending = null; }
        if (t.badge == Track.Badge.PAUSED) { show(t); return t; }

        boolean same = t.badge == pending && eq(t.badgeText, pendingText);
        if (!same) { pending = t.badge; pendingText = t.badgeText; pendingSince = now; }
        // starting playback (nothing shown yet, or coming from Paused) waits for the A6's MQA detection
        long need = shown == null || shown == Track.Badge.PAUSED || shown == Track.Badge.NONE ? START_MS : STEADY_MS;
        boolean alreadyShown = t.badge == shown && eq(t.badgeText, shownText);
        if (alreadyShown || now - pendingSince >= need) { show(t); return t; }

        // 1.0.8: the wait shows a grey "Checking…" pill (pulsing dot) instead of a blank, so it never looks broken
        if (shown == null || shown == Track.Badge.PAUSED) { t.badge = Track.Badge.CHECKING; t.badgeText = "Checking…"; t.badgeNote = ""; t.mqaOut = false; }
        else { t.badge = shown; t.badgeText = shownText; t.badgeNote = shownNote; t.mqaOut = shownMqa; }
        return t;
    }

    /**
     * 1.0.12-2: forget the shown verdict, so the badge says Checking until a fresh one is steady (START_MS).
     * Called when EverSignal comes back to the front: Michael (2026-11-20) turned EQ on in the stock screen,
     * came back, and saw the old Bit-perfect for a moment before EQ active popped in.
     */
    void reset() { shown = null; pending = null; }

    private void show(Track t) { shown = t.badge; shownText = t.badgeText; shownNote = t.badgeNote; shownMqa = t.mqaOut; }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
}
