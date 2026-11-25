package com.malbers.eversignal;

import static org.junit.Assert.*;

import org.junit.Test;

/** 1.0.14: "Converted to DSD" and the downmix wording. */
public class Labels114Test {
    private static Track pcm(double inHz, String outCodec, Double outHz) {
        Track t = new Track();
        t.ownPlayer = true;
        t.container = "FLAC";
        t.rateHz = inHz;
        t.bits = 16.0;
        t.outCodec = outCodec;
        t.outRateHz = outHz;
        t.volumeOk = true;
        return t;
    }

    @Test public void pcmOutAsDsdSaysConvertedToDsd() {
        Track t = pcm(44100, "DSD 128", 5644800.0);
        t.computeBadge(true);
        assertEquals(Track.Badge.RESAMPLED, t.badge);
        assertEquals("Converted to DSD", t.badgeText);
        assertTrue(t.badgeNote, t.badgeNote.endsWith("→ DSD128 out"));
    }

    @Test public void pcmUpsamplingStillSaysResampled() {
        Track t = pcm(44100, "PCM", 88200.0);
        t.computeBadge(true);
        assertEquals("Resampled", t.badgeText);
    }

    @Test public void downmixOnlyOnStereoPorts() {
        Track t = new Track();
        t.channels = 6.0;
        t.ifaceRaw = "OPT";
        assertEquals(" · 5.1 downmixed to 2 ch", t.downmixNote());
        t.ifaceRaw = "XLRRCA";
        assertEquals(" · 5.1 downmixed to 2 ch", t.downmixNote());
        t.ifaceRaw = "HDMI";
        assertEquals("", t.downmixNote());
        t.ifaceRaw = "USB";   // a USB DAC could be multichannel: say nothing
        assertEquals("", t.downmixNote());
        t.ifaceRaw = "OPT";
        t.channels = 2.0;
        assertEquals("", t.downmixNote());
    }

    @Test public void soundStyleIsNeverBitPerfect() {
        Track t = pcm(44100, "PCM", 44100.0);   // Arches, FLAC 44.1/24, PCM 44.1 out, style on (2026-11-25)
        t.computeBadge(true);
        assertEquals("Bit-perfect", t.badgeText);
        t.style = true;
        t.computeBadge(true);
        assertEquals(Track.Badge.EQ_ACTIVE, t.badge);
        assertEquals("Sound style on", t.badgeText);
    }

    @Test public void olderFirmwareWithoutTheFieldMeansNoStyle() {
        // firmware before 1.6.40 has no voiceStyleActive at all: no error, no style, the old verdict
        java.util.Map<String, String> s = new java.util.HashMap<>();
        s.put("playType", "5");
        s.put("state", "3");
        s.put("playingMusic.title", "Arches");
        s.put("playingMusic.extension", "flac");
        s.put("playingMusic.sampleRateNumber", "44100");
        s.put("playingMusic.bits", "24");
        Track t = Track.from(s, true);
        assertFalse(t.style);
        assertFalse(t.styleKnown);   // no EVS line on older firmware
        s.put("voiceStyleActive", "true");
        Track n = Track.from(s, true);
        assertTrue(n.style);
        assertTrue(n.styleKnown);
    }

    @Test public void sacdIsoShowsBothAreasAndDst() {
        // Genesis Invisible Touch SACD ISO on the Gen 1, fw 1.6.40, 2026-11-25 (same on USB and HDMI)
        java.util.Map<String, String> s = new java.util.HashMap<>();
        s.put("playType", "5");
        s.put("state", "3");
        s.put("playingMusic.title", "Invisible Touch");
        s.put("playingMusic.extension", "SACD");
        s.put("playingMusic.codec", "DST 64");
        s.put("playingMusic.sampleRateNumber", "2822400");
        s.put("playingMusic.bits", "1");
        s.put("playingMusic.channels", "2");
        s.put("playingMusic.channelsSecond", "6");
        Track t = Track.from(s, true);
        assertEquals("2&6 ch", t.chText());
        assertEquals("1-bit · 2&6 ch", t.depthChannels());
        assertEquals("DST64", t.dstName());
        s.remove("playingMusic.channelsSecond");   // an ordinary DSF: no second area
        s.put("playingMusic.codec", "");
        Track d = Track.from(s, true);
        assertEquals("2 ch", d.chText());
        assertNull(d.dstName());
    }

    @Test public void processingLeadsWithWhatIsOn() {
        Track t = new Track();
        t.styleKnown = true;
        assertArrayEquals(new String[]{"PROCESSING", "EQ off", "DSP off", "Sub off", "EVS off"}, ScreenView.processing(t));
        t.style = true;
        assertArrayEquals(new String[]{"PROCESSING", "EVS on", "EQ off", "DSP off", "Sub off"}, ScreenView.processing(t));
        t.dsp = true;   // EVS outranks DSP
        assertArrayEquals(new String[]{"PROCESSING", "EVS on", "EQ off", "DSP on", "Sub off"}, ScreenView.processing(t));
        Track old = new Track();   // older firmware: no EVS line at all
        old.sub = true;
        assertArrayEquals(new String[]{"PROCESSING", "Sub on", "EQ off", "DSP off"}, ScreenView.processing(old));
    }
}
