package com.malbers.eversignal;

/**
 * 1.0.6: while a local file is paused, the A6 reports a fallback output (PCM 192 kHz whatever the file;
 * measured 2026-11-16 with a 96 kHz WAV and an MQA FLAC), and it lags about a second after play/pause.
 * So the output (codec, rate, MQA) is held from the last steady PLAYING moment of the same track, while
 * everything else (EQ, DSP, volume) stays live and the verdict is recalculated from it. With no playing
 * reading for this track yet, the badge says Paused instead of guessing. TIDAL reports the real output
 * while paused, so there the held value simply equals the live one.
 *
 * Only an output that has stayed the same for STEADY_MS of playback is held: at pause the A6 can still say
 * "playing" for a moment while already reporting the 192 kHz fallback, and that blip must never be the one
 * kept (seen 2026-11-16: paused MQA showed Resampled).
 */
final class OutputHold {
    static final long STEADY_MS = 2000;

    private String key, codec;
    private Double rate;
    private boolean mqa;

    private String cKey, cCodec;
    private Double cRate;
    private boolean cMqa;
    private long cSince;

    /** playingSettled: the player says playing, and has been for the settle time since a start or track change. */
    Track apply(Track t, boolean playingSettled, long now) {
        if (playingSettled && t.outCodec != null) {
            boolean same = t.key.equals(cKey) && t.outCodec.equals(cCodec) && same(t.outRateHz, cRate) && t.mqaOut == cMqa;
            if (!same) { cKey = t.key; cCodec = t.outCodec; cRate = t.outRateHz; cMqa = t.mqaOut; cSince = now; }
            if (now - cSince >= STEADY_MS) { key = cKey; codec = cCodec; rate = cRate; mqa = cMqa; }
            return t;
        }
        cKey = null;   // a new steady stretch starts with the next playing reading
        if (key != null && key.equals(t.key)) {
            t.outCodec = codec; t.outRateHz = rate; t.mqaOut = mqa;
            t.computeBadge(true);
        } else if (t.state != 3) {
            t.outCodec = null; t.outRateHz = null; t.mqaOut = false;
            t.badge = Track.Badge.PAUSED; t.badgeText = "Paused"; t.badgeNote = "press play for the signal path";
        }
        return t;
    }

    private static boolean same(Double a, Double b) { return a == null ? b == null : b != null && Math.abs(a - b) < 1; }
}
