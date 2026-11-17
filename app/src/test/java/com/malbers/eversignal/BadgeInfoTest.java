package com.malbers.eversignal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class BadgeInfoTest {

    private static Track pcm(double in, double out, boolean eq, boolean volOk) {
        Track t = new Track();
        t.rateHz = in; t.outRateHz = out; t.outCodec = "PCM"; t.eq = eq; t.volumeOk = volOk; t.volume = volOk ? "0dB" : "-12dB";
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
}
