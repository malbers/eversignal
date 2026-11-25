package com.malbers.eversignal;

import static org.junit.Assert.*;

import org.junit.Test;

/** 1.0.14: ALAC rate from the file, bogus player rates ignored, Apple Music tier from qualityIcon (2026-11-25). */
public class Fixes114Test {
    @Test public void alacConfigCarriesTheRealRate() {
        byte[] b = new byte[28];          // version/flags 4 + ALACSpecificConfig 24
        b[4 + 5] = 24;                    // bitDepth
        b[4 + 9] = 2;                     // numChannels
        int r = 96000;                    // sampleRate, big-endian at config offset 20
        b[24] = (byte) (r >>> 24); b[25] = (byte) (r >>> 16); b[26] = (byte) (r >>> 8); b[27] = (byte) r;
        assertEquals(96000.0, M4aCodec.alacRate(b), 0.1);
        assertNull(M4aCodec.alacRate(new byte[10]));
    }

    @Test public void playerRatesBelow8kAreUnknown() {
        assertNull(Track.realRate(1.0));      // fw 1.6.40: ALAC 24/96 from the Library = "0.001 kHz"
        assertNull(Track.realRate(null));
        assertEquals(44100.0, Track.realRate(44100.0), 0.1);
    }

    @Test public void headerRateFillsOnlyAMissingRate() {
        Track t = new Track();
        t.m4a = true;
        assertTrue(t.applyHeaderRate(96000.0, true));
        assertEquals(96000.0, t.rateHz, 0.1);
        assertFalse(t.applyHeaderRate(48000.0, true));   // a real player rate is never overwritten
    }

    @Test public void appleMusicTierFromTheIconName() {
        assertEquals("Hi-Res Lossless",
            Track.tierFromIcon("/SystemSettings/getItemSettingIcon?iconName=applemusic_hires_lossless.png"));
        assertEquals("Lossless", Track.tierFromIcon("/SystemSettings/getItemSettingIcon?iconName=applemusic_lossless.png"));
        assertEquals("Dolby Atmos", Track.tierFromIcon("/x?iconName=applemusic_dolby_atmos.png"));
        assertNull(Track.tierFromIcon(""));
        assertNull(Track.tierFromIcon(null));
    }
}
