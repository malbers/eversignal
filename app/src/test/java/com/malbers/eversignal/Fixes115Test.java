package com.malbers.eversignal;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** 1.0.15-beta.2: full-screen chrome, DTS lossy, DR on SACD ISO, Back one level (2026-11-25). */
public class Fixes115Test {

    // ---------------------------------------------------------------- (4) Full-Screen Album Mode chrome

    @Test public void chromeShownOnOpenHiddenOnAutoFlip() {
        ViewerChrome v = new ViewerChrome();
        v.reset("t1");
        assertTrue(v.shown());
        assertEquals(1f, v.alpha(10_000), 0.001f);         // no fade-in on open
        v.hide(10_000);                                    // auto flip
        assertFalse(v.shown());
        assertEquals(0.5f, v.alpha(10_000 + ViewerChrome.FADE_MS / 2), 0.01f);
        assertEquals(0f, v.alpha(10_000 + ViewerChrome.FADE_MS), 0.001f);
        assertTrue(v.fading(10_100));
        assertFalse(v.fading(10_000 + ViewerChrome.FADE_MS));
    }

    @Test public void manualStepBringsItBackUntilTheNextAutoFlip() {
        ViewerChrome v = new ViewerChrome();
        v.reset("t1");
        v.hide(1000);
        v.show(5000);                                      // swipe
        assertTrue(v.shown());
        assertEquals(1f, v.alpha(5000 + ViewerChrome.FADE_MS), 0.001f);
        v.hide(15_000);                                    // next auto flip
        assertFalse(v.shown());
    }

    @Test public void newTrackShowsItAgainSameTrackDoesNot() {
        ViewerChrome v = new ViewerChrome();
        v.reset("t1");
        v.hide(1000);
        v.track("t1", 2000);
        assertFalse(v.shown());
        v.track("t2", 3000);
        assertTrue(v.shown());
        ViewerChrome w = new ViewerChrome();
        w.reset(null);
        w.hide(1000);
        w.track("t1", 2000);                              // first key seen is not a "new" track
        assertFalse(w.shown());
    }

    // ---------------------------------------------------------------- (1) DTS is lossy

    private static Track own(String ext, String codec, double bits) {
        Map<String, String> s = new HashMap<>();
        s.put("playType", "5");
        s.put("state", "3");
        s.put("playingMusic.title", "Look at Yourself");
        s.put("playingMusic.extension", ext);
        if (codec != null) s.put("playingMusic.codec", codec);
        s.put("playingMusic.sampleRateNumber", "48000");
        s.put("playingMusic.bits", String.valueOf((int) bits));
        s.put("playingMusic.channels", "6");
        return Track.from(s, true);
    }

    /** Pierre C.'s Uriah Heep DVD .dts: Source DTS 48 kHz / 32-bit / 6 ch read Bit-perfect. DTS core is lossy. */
    @Test public void dtsCoreIsLossy() {
        Track t = own("dts", "DTS", 32);
        assertTrue(t.lossy);
        assertEquals(Track.Badge.LOSSY, t.badge);
        assertNull(t.bits);                                // the decoder's depth, not the file's
        assertTrue(Track.isDtsCore("DTS", null));
        assertTrue(Track.isDtsCore(null, "DTS 5.1"));
        assertTrue(Track.isDtsCore("MKV", "dca"));
    }

    /** DTS-HD Master Audio is lossless: left alone until one is seen. */
    @Test public void dtsHdLeftAlone() {
        assertFalse(Track.isDtsCore("DTS", "DTS-HD MA"));
        assertFalse(Track.isDtsCore("DTSHD", null));
        assertFalse(Track.isDtsCore(null, "DTS:X"));
        assertFalse(Track.isDtsCore("FLAC", null));
        assertFalse(own("flac", null, 24).lossy);
    }

    // ---------------------------------------------------------------- (5) DR on an SACD ISO

    @Test public void isoIsDsdForDr() {
        assertTrue(DrScan.isDsd("/storage/X/EverSoloMusic/ISO/Depeche Mode/1997 - Ultra (DMCD9 2007 SACD Remaster).iso"));
        assertTrue(DrScan.isDsd("a.ISO"));
        assertTrue(DrScan.isDsd("a.dsf"));
        assertFalse(DrScan.isDsd("a.flac"));
    }

    // ---------------------------------------------------------------- (7) Back steps out one level

    @Test public void backStepsOutOneLevel() {
        assertEquals(BackStep.Step.CLOSE_VIEWER, BackStep.of(true, true, true, true, true));
        assertEquals(BackStep.Step.CLOSE_DIALOG, BackStep.of(false, true, false, true, true));
        assertEquals(BackStep.Step.CLOSE_FEEDBACK, BackStep.of(false, false, true, true, true));
        assertEquals(BackStep.Step.LEAVE_SETTINGS, BackStep.of(false, false, false, true, true));
        assertEquals(BackStep.Step.TO_SIMPLE, BackStep.of(false, false, false, false, true));
        assertEquals(BackStep.Step.LEAVE_APP, BackStep.of(false, false, false, false, false));
    }
}
