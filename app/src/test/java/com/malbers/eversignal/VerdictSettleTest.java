package com.malbers.eversignal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Replays the EQ switch Michael saw on 2026-11-16: Bit-perfect, MQA ×8, Resampled, EQ active. */
public class VerdictSettleTest {

    private static Track t(Track.Badge b, String text) {
        Track t = new Track();
        t.key = "own|1|Ashokan Farewell|Burr"; t.state = 3; t.badge = b; t.badgeText = text; t.badgeNote = text + " note";
        return t;
    }

    @Test public void noBadgeUntilTheFirstVerdictIsSteady() {
        VerdictSettle v = new VerdictSettle();
        assertEquals(Track.Badge.CHECKING, v.apply(t(Track.Badge.BIT_PERFECT, "Bit-perfect"), 0).badge);      // MQA not detected yet
        assertEquals(Track.Badge.CHECKING, v.apply(t(Track.Badge.MQA, "MQA unfolded ×8"), 1000).badge);
        assertEquals(Track.Badge.CHECKING, v.apply(t(Track.Badge.MQA, "MQA unfolded ×8"), 3000).badge);      // steady 2 s, need 3
        assertEquals(Track.Badge.MQA, v.apply(t(Track.Badge.MQA, "MQA unfolded ×8"), 4000).badge);       // steady 3 s
    }

    @Test public void inBetweenOutputsDoNotFlash() {
        VerdictSettle v = new VerdictSettle();
        v.apply(t(Track.Badge.MQA, "MQA unfolded ×8"), 0);
        v.apply(t(Track.Badge.MQA, "MQA unfolded ×8"), 3000);                                             // shown: MQA
        Track r = v.apply(t(Track.Badge.RESAMPLED, "Resampled"), 4000);                                   // EQ switching
        assertEquals("MQA unfolded ×8", r.badgeText);
        assertEquals("MQA unfolded ×8 note", r.badgeNote);
        assertEquals(Track.Badge.MQA, v.apply(t(Track.Badge.EQ_ACTIVE, "EQ active"), 5000).badge);
        assertEquals(Track.Badge.MQA, v.apply(t(Track.Badge.EQ_ACTIVE, "EQ active"), 6500).badge);       // 1.5 s steady
        assertEquals(Track.Badge.EQ_ACTIVE, v.apply(t(Track.Badge.EQ_ACTIVE, "EQ active"), 7000).badge); // 2 s steady
    }

    @Test public void pausedShowsAtOnce() {
        VerdictSettle v = new VerdictSettle();
        Track p = t(Track.Badge.PAUSED, "Paused");
        p.state = 4;
        assertEquals(Track.Badge.PAUSED, v.apply(p, 0).badge);
    }

    /** Seen 2026-11-16: Paused, press play, Bit-perfect flashed before MQA ×8. From Paused it waits 3 s too. */
    @Test public void playFromPausedWaitsForMqaDetection() {
        VerdictSettle v = new VerdictSettle();
        Track p = t(Track.Badge.PAUSED, "Paused");
        p.state = 4;
        v.apply(p, 0);
        assertEquals(Track.Badge.CHECKING, v.apply(t(Track.Badge.BIT_PERFECT, "Bit-perfect"), 1000).badge);    // playing: no "Paused"
        assertEquals(Track.Badge.CHECKING, v.apply(t(Track.Badge.BIT_PERFECT, "Bit-perfect"), 3000).badge);    // 2 s: not yet
        assertEquals(Track.Badge.CHECKING, v.apply(t(Track.Badge.MQA, "MQA unfolded ×8"), 3500).badge);        // detected
        assertEquals(Track.Badge.MQA, v.apply(t(Track.Badge.MQA, "MQA unfolded ×8"), 6500).badge);
    }

    @Test public void newTrackStartsOver() {
        VerdictSettle v = new VerdictSettle();
        v.apply(t(Track.Badge.BIT_PERFECT, "Bit-perfect"), 0);
        v.apply(t(Track.Badge.BIT_PERFECT, "Bit-perfect"), 3000);
        Track other = t(Track.Badge.RESAMPLED, "Resampled");
        other.key = "own|2|Other|Someone";
        assertEquals(Track.Badge.CHECKING, v.apply(other, 3500).badge);
    }
}
