package com.malbers.eversignal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class BadgeInfoTest {

    private static Track pcm(double in, double out, boolean eq, boolean volOk) {
        Track t = new Track();
        t.ownPlayer = true; t.rateHz = in; t.outRateHz = out; t.outCodec = "PCM"; t.eq = eq; t.volumeOk = volOk; t.volume = volOk ? "0dB" : "-12dB";
        t.computeBadge(true);
        return t;
    }

    private static String all(List<String> l) { return String.join("\n", l); }

    /** Passthrough is full volume: on EVERY badge panel, any line that mentions it is followed by the warning. */
    @Test public void everyPassthroughMentionCarriesTheWarning() {
        int mentions = 0;
        for (Track.Badge b : Track.Badge.values()) {
            for (boolean volOk : new boolean[]{true, false}) {
                Track t = pcm(44100, 96000, true, volOk);
                t.badge = b;
                List<String> l = BadgeInfo.lines(t);
                for (int i = 0; i < l.size(); i++) {
                    String line = l.get(i);
                    if (line.toLowerCase().contains("passthrough") && !line.equals(BadgeInfo.WARN)) {
                        mentions++;
                        assertTrue(b + " volOk=" + volOk + ": no warning after \"" + line + "\"",
                            i + 1 < l.size() && l.get(i + 1).equals(BadgeInfo.WARN));
                    }
                }
            }
        }
        assertTrue(mentions >= 8);   // guard: the test must actually find the panels it protects
    }

    @Test public void bitPerfectHasNoChecklist() {
        Track t = pcm(192000, 192000, false, true);
        assertEquals(Track.Badge.BIT_PERFECT, t.badge);
        String s = all(BadgeInfo.lines(t));
        assertTrue(s.startsWith("Bit-perfect:"));
        assertFalse(s.contains("To get Bit-perfect"));
        assertFalse(s.contains("DMP-A6"));
    }

    @Test public void resampledExplainsItselfAndPutsVolumeFirst() {
        Track t = pcm(44100, 96000, false, false);
        assertEquals(Track.Badge.RESAMPLED, t.badge);
        List<String> l = BadgeInfo.lines(t);
        assertTrue(l.get(0).startsWith("Resampled:"));
        int to = l.indexOf("To get Bit-perfect:");
        assertTrue(to > 0);
        assertTrue(l.get(to + 1).contains("volume passthrough"));
        assertEquals(BadgeInfo.WARN, l.get(to + 2));
        assertTrue(l.get(to + 3).startsWith("•  Fix: turn off upsampling"));
        assertTrue(l.get(to + 4).startsWith("•  OK: EQ and DSP off"));
    }

    @Test public void volumeBadgeShowsOnlyVolumeToFix() {
        Track t = pcm(48000, 48000, false, false);
        assertEquals(Track.Badge.VOLUME, t.badge);
        String s = all(BadgeInfo.lines(t));
        assertTrue(s.contains("•  Fix: turn on volume passthrough (0 dB)"));
        assertTrue(s.contains("Turn it down first: passthrough is full volume."));
        assertTrue(s.contains("•  OK: same sample rate in and out"));
        assertTrue(s.contains("•  OK: EQ and DSP off"));
    }

    @Test public void eqBadgeAsksToTurnEqOff() {
        Track t = pcm(96000, 96000, true, true);
        assertEquals(Track.Badge.EQ_ACTIVE, t.badge);
        String s = all(BadgeInfo.lines(t));
        assertTrue(s.startsWith("EQ active:"));
        assertTrue(s.contains("•  OK: volume at 0 dB (passthrough)"));
        assertTrue(s.contains("Turn it down first"));   // passthrough is never mentioned without the caution
        assertTrue(s.contains("•  Fix: turn EQ off"));
    }

    @Test public void everyPanelEndsWithClose() {
        Track t = pcm(44100, 44100, false, true);
        List<String> l = BadgeInfo.lines(t);
        assertEquals("Tap anywhere to close.", l.get(l.size() - 1));
    }

    private static Track dsd(String outCodec, boolean eq) {
        Track t = new Track();
        t.dsd = true; t.rateHz = 5644800.0; t.outCodec = outCodec; t.outRateHz = "PCM".equals(outCodec) ? 176400.0 : 5644800.0;
        t.eq = eq; t.volumeOk = true; t.volume = "0dB";
        t.computeBadge(true);
        return t;
    }

    /** Pierre C., 2026-11-19: EQ on + DSD native looked like a bug. Measured: the A6 skips EQ for native DSD. */
    @Test public void dsdNativeWithEqSaysEqIsSkipped() {
        Track t = dsd("DSD128", true);
        assertEquals(Track.Badge.DSD_NATIVE, t.badge);
        assertTrue(t.badgeNote, t.badgeNote.endsWith("· EQ skipped"));
        assertTrue(all(BadgeInfo.lines(t)).contains(BadgeInfo.EQ_BYPASS));
        Track off = dsd("DSD128", false);
        assertFalse(off.badgeNote.contains("EQ"));
        assertFalse(all(BadgeInfo.lines(off)).contains("EQ"));
    }

    /** The Eversolo's EQ screen: EQ only applies to its own player, stereo PCM, below 192 kHz. */
    @Test public void eqBypassRules() {
        Track hi = pcm(352800, 352800, true, true);
        assertEquals(Track.Badge.BIT_PERFECT, hi.badge);
        assertTrue(hi.badgeNote.endsWith("· EQ skipped"));
        assertTrue(all(BadgeInfo.lines(hi)).contains(BadgeInfo.EQ_BYPASS));

        assertEquals(Track.Badge.EQ_ACTIVE, pcm(96000, 96000, true, true).badge);
        assertEquals(Track.Badge.EQ_ACTIVE, pcm(192000, 192000, true, true).badge);   // 192 itself: applied until measured

        Track cast = new Track();
        cast.rateHz = 96000.0; cast.outRateHz = 96000.0; cast.outCodec = "PCM"; cast.eq = true; cast.volumeOk = true; cast.volume = "0dB";
        cast.computeBadge(true);
        assertEquals(Track.Badge.BIT_PERFECT, cast.badge);   // not the internal player: bypassed

        Track mc = pcm(48000, 48000, true, true);
        mc.channels = 6.0; mc.computeBadge(true);
        assertEquals(Track.Badge.BIT_PERFECT, mc.badge);
        assertFalse(pcm(96000, 96000, false, true).badgeNote.contains("EQ"));
    }

    /** 1.0.12.6: an Android app (playType 8) never gets Bit-perfect, even when the rates line up (EOS can't be read). */
    @Test public void externalAppNeverBitPerfect() {
        Track a = new Track();
        a.externalApp = true; a.playerLabel = "Apple Music";
        a.rateHz = 44100.0; a.outRateHz = 44100.0; a.outCodec = "PCM"; a.volumeOk = true; a.volume = "0dB";
        a.computeBadge(true);
        assertEquals(Track.Badge.EXTERNAL_APP, a.badge);
        assertTrue(a.badgeNote.endsWith("needs EOS on"));
        assertTrue(all(BadgeInfo.lines(a)).contains("Turn EOS on"));

        Track b = new Track();   // the A6 reports no source rate for the app: output only
        b.externalApp = true; b.outRateHz = 192000.0; b.outCodec = "PCM"; b.volumeOk = true;
        b.computeBadge(true);
        assertEquals(Track.Badge.EXTERNAL_APP, b.badge);
        assertTrue(b.badgeNote.startsWith("192kHz out") || b.badgeNote.contains("out"));
        assertFalse(b.badgeNote.contains(" in "));
    }

    @Test public void dsdToPcmMakesNoEqClaim() {
        assertFalse(all(BadgeInfo.lines(dsd("PCM", true))).contains("EQ off"));
    }
}
