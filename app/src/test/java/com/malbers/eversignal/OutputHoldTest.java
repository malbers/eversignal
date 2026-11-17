package com.malbers.eversignal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The pause fallback measured on the A6 2026-11-16: playing reports the real output, paused reports PCM 192 kHz. */
public class OutputHoldTest {

    private static Track wav96(int state, double outRate, boolean eq) {
        Track t = new Track();
        t.key = "own|1|Ashokan Farewell|Burr";
        t.state = state; t.rateHz = 96000.0; t.outCodec = "PCM"; t.outRateHz = outRate; t.eq = eq; t.volumeOk = true;
        t.computeBadge(true);
        return t;
    }

    private static Track mqa(int state, String codec, double outRate) {
        Track t = new Track();
        t.key = "own|31729|Ashokan Farewell|Burr";
        t.state = state; t.rateHz = 44100.0; t.outCodec = codec; t.outRateHz = outRate; t.mqaOut = codec.equals("MQA");
        t.volumeOk = true;
        t.computeBadge(true);
        return t;
    }

    /** Plays steadily for 2 s so the output is held. */
    private static OutputHold played(Track playing) {
        OutputHold h = new OutputHold();
        h.apply(playing, true, 0);
        h.apply(playing, true, 2000);
        return h;
    }

    @Test public void pausedKeepsThePlayingOutputAndVerdict() {
        OutputHold h = played(wav96(3, 96000, false));
        Track paused = h.apply(wav96(4, 192000, false), false, 3000);
        assertEquals(96000.0, paused.outRateHz, 0.1);
        assertEquals(Track.Badge.BIT_PERFECT, paused.badge);
    }

    @Test public void resumeLagDoesNotFlashResampled() {
        OutputHold h = played(wav96(3, 96000, false));
        Track resuming = h.apply(wav96(3, 192000, false), false, 3000);   // playing again, not settled, still says 192
        assertEquals(Track.Badge.BIT_PERFECT, resuming.badge);
    }

    @Test public void eqStaysLiveWhilePaused() {
        OutputHold h = played(wav96(3, 96000, false));
        Track paused = h.apply(wav96(4, 192000, true), false, 3000);       // EQ switched on while paused
        assertEquals(Track.Badge.EQ_ACTIVE, paused.badge);
    }

    @Test public void pausedWithNoPlayingReadingSaysPaused() {
        Track paused = new OutputHold().apply(wav96(4, 192000, false), false, 0);
        assertEquals(Track.Badge.PAUSED, paused.badge);
        assertNull(paused.outRateHz);
    }

    @Test public void anotherTracksHoldIsNotUsed() {
        OutputHold h = played(wav96(3, 96000, false));
        Track other = wav96(4, 192000, false);
        other.key = "own|2|Something else|Someone";
        assertEquals(Track.Badge.PAUSED, h.apply(other, false, 3000).badge);
    }

    /** At pause the A6 can still say playing for a moment while already reporting 192 kHz: never hold that blip. */
    @Test public void pauseBlipIsNotHeld() {
        OutputHold h = played(mqa(3, "MQA", 352800));
        h.apply(mqa(3, "PCM", 192000), true, 3000);                         // still "playing", already the fallback
        Track paused = h.apply(mqa(4, "PCM", 192000), false, 4000);
        assertEquals(Track.Badge.MQA, paused.badge);
        assertEquals("MQA unfolded ×8", paused.badgeText);
    }

    @Test public void shortPlayIsNotHeld() {
        OutputHold h = new OutputHold();
        h.apply(wav96(3, 96000, false), true, 0);                           // under 2 s of steady output
        assertEquals(Track.Badge.PAUSED, h.apply(wav96(4, 192000, false), false, 1500).badge);
    }
}
