package com.malbers.eversignal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Every fixture is a real getState reply captured from a DMP-A6 on 2026-11-15 (MAC addresses redacted). */
public class TrackTest {

    private static Track load(String name) throws Exception {
        try (InputStream in = TrackTest.class.getResourceAsStream("/fixtures/" + name + ".json")) {
            Map<String, String> s = Flat.of(new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            return Track.from(s, true);
        }
    }

    /** Captured 2026-11-17: an ALAC .m4a (24-bit / 96 kHz) was badged "Lossy (M4A)", reported by an owner. */
    @Test public void alacM4aIsLossless() throws Exception {
        Track t = load("m4a-alac-24-96");
        assertEquals("ALAC", t.container);
        assertFalse(t.lossy);
        assertTrue(t.badge != Track.Badge.LOSSY);
    }

    /** The live state says 48 kHz for that 96 kHz file; the queue entry has 96000, and wins for .m4a. */
    @Test public void alacM4aTakesTheQueueRate() throws Exception {
        Track t = load("m4a-alac-24-96");
        assertEquals(48000.0, t.rateHz, 0.1);
        Map<String, String> q = new java.util.HashMap<>();
        q.put("title", "01 ALAC 24-96"); q.put("sampleRateNumber", "96000"); q.put("bits", "24");
        assertTrue(t.applyQueueFile(q, true));
        assertEquals(96000.0, t.rateHz, 0.1);
        assertEquals(Track.Badge.BIT_PERFECT, t.badge);   // PCM 96 kHz out, EQ off, 0 dB
    }

    /** Seen 2026-11-17: the queue titled the file by its tag, the state by its file name; the id (32592) is the same. */
    @Test public void queueEntryMatchesByIdWhenTitlesDiffer() throws Exception {
        Track t = load("m4a-alac-24-96");
        Map<String, String> q = new java.util.HashMap<>();
        q.put("id", "32592"); q.put("title", "EverSignal test ALAC 24-96"); q.put("sampleRateNumber", "96000"); q.put("bits", "24");
        assertTrue(t.applyQueueFile(q, true));
        assertEquals(96000.0, t.rateHz, 0.1);
        assertEquals(Track.Badge.BIT_PERFECT, t.badge);
    }

    /** Seen 2026-11-17, started from the file browser: same title, but the queue's id is 735278916, the state's 32592. */
    @Test public void queueEntryMatchesByTitleWhenIdsDiffer() throws Exception {
        Track t = load("m4a-alac-24-96");
        Map<String, String> q = new java.util.HashMap<>();
        q.put("id", "735278916"); q.put("title", "01 ALAC 24-96"); q.put("sampleRateNumber", "96000"); q.put("bits", "24");
        assertTrue(t.applyQueueFile(q, true));
        assertEquals(96000.0, t.rateHz, 0.1);
        assertEquals(Track.Badge.BIT_PERFECT, t.badge);
    }

    @Test public void queueEntryWithNeitherIdNorTitleIsIgnored() throws Exception {
        Track t = load("m4a-alac-24-96");
        Map<String, String> q = new java.util.HashMap<>();
        q.put("id", "653374469"); q.put("title", "02 AAC 256k"); q.put("sampleRateNumber", "44100");
        assertFalse(t.applyQueueFile(q, true));
        assertEquals(48000.0, t.rateHz, 0.1);
    }

    @Test public void queueEntryForAnotherSongIsIgnored() throws Exception {
        Track t = load("m4a-alac-24-96");
        Map<String, String> q = new java.util.HashMap<>();
        q.put("title", "02 AAC 256k"); q.put("sampleRateNumber", "44100");
        assertFalse(t.applyQueueFile(q, true));
        assertEquals(48000.0, t.rateHz, 0.1);
    }

    @Test public void aacM4aStaysLossy() throws Exception {
        Track t = load("m4a-aac-256");
        assertEquals("AAC", t.container);
        assertTrue(t.lossy);
        assertEquals(Track.Badge.LOSSY, t.badge);
    }

    /** Captured 2026-11-17: a FLAC started from the file browser reports type 2, not 1. Its cover must still be asked for. */
    @Test public void fileBrowserPlayGetsACover() throws Exception {
        Track t = load("filebrowser-type2");
        assertEquals("01 Why", t.title);
        assertNull(t.artUrl);
        assertEquals("29571", t.artId);
    }

    @Test public void libraryPlayStillGetsACover() throws Exception {
        Track t = load("wav192");
        assertNull(t.artUrl);
        assertTrue(t.artId != null);
    }

    @Test public void wavIsBitPerfect() throws Exception {
        Track t = load("wav192");
        assertEquals("Ashokan Farewell", t.title);
        assertEquals("WAV", t.container);
        assertEquals(Track.Badge.BIT_PERFECT, t.badge);
        assertEquals("192kHz in = 192kHz out", t.badgeNote);
        assertEquals("XLR/RCA", t.iface);
        assertTrue(t.analogPort());
    }

    @Test public void dsfIsDsdNative() throws Exception {
        Track t = load("dsd256");
        assertTrue(t.dsd);
        assertEquals(Track.Badge.DSD_NATIVE, t.badge);
        assertEquals("DSD256 in → DSD256 out", t.badgeNote);
        assertEquals(1.0, t.bits, 0.0);
        assertEquals("11.29 MHz", Track.rateText(t.rateHz, true));
        assertEquals("Multichannel", t.sacdArea); // fixture sacdArea = 1
    }

    @Test public void mqaFlacUnfoldsTimesEight() throws Exception {
        Track t = load("mqa-flac");
        assertEquals(Track.Badge.MQA, t.badge);
        assertEquals("MQA unfolded ×8", t.badgeText);
        assertEquals("44.1kHz in → 352.8kHz out", t.badgeNote);
    }

    @Test public void mp3IsLossyWithNoBitDepth() throws Exception {
        Track t = load("mp3");
        assertTrue(t.lossy);
        assertNull(t.bits);
        assertEquals(Track.Badge.LOSSY, t.badge);
        assertEquals("Lossy (MP3)", t.badgeText);
    }

    /** In-app TIDAL DOES report output once it has filled in (an earlier "never reported" read was wrong). */
    @Test public void tidalInAppIsBitPerfectOnceOutputArrives() throws Exception {
        Track t = load("tidal-inapp");
        assertTrue(t.ownPlayer);
        assertEquals("PCM", t.outCodec);
        assertEquals(Track.Badge.BIT_PERFECT, t.badge);
        assertTrue(t.artUrl.startsWith("https://resources.tidal.com/"));
    }

    /** Before the output fills in, a stream falls back to "Hi-res stream" rather than guessing. */
    @Test public void tidalInAppWithoutOutputYetIsHiResStream() throws Exception {
        try (InputStream in = TrackTest.class.getResourceAsStream("/fixtures/tidal-inapp.json")) {
            JSONObject j = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            JSONObject out = j.getJSONObject("everSoloPlayInfo").getJSONObject("everSoloPlayOutputInfo");
            out.put("outPutDecodec", "");
            out.put("outPutSampleRate", 0);
            Track t = Track.from(Flat.of(j), true);
            assertEquals(Track.Badge.HIRES_STREAM, t.badge);
        }
    }

    @Test public void tidalConnectReadsTheConnectBlockNotStalePlayingMusic() throws Exception {
        Track t = load("tidal-connect");
        assertFalse(t.ownPlayer);
        assertEquals("Oye Como Va", t.title);
        assertEquals("Santana", t.artist);
        assertEquals("TIDAL Connect", t.playerLabel);
        assertEquals(Track.Badge.BIT_PERFECT, t.badge);
        assertNull(t.bitrate);
        assertFalse(t.hasQueue);
    }

    @Test public void airplayIsLossyAacWithArtOnThePlayer() throws Exception {
        Track t = load("airplay");
        assertEquals("AirPlay", t.playerLabel);
        assertEquals("AAC", t.container);
        assertEquals(Track.Badge.LOSSY, t.badge);
        assertEquals("AirPlay · no lossless path", t.badgeNote);
        assertTrue(t.artPath == null || t.artPath.startsWith("/SystemSettings/"));
    }

    @Test public void unsettledOutputShowsNoBadgeInsteadOfAStaleOne() throws Exception {
        try (InputStream in = TrackTest.class.getResourceAsStream("/fixtures/wav192.json")) {
            Track t = Track.from(Flat.of(new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8))), false);
            assertNull(t.outCodec);
            assertEquals(Track.Badge.NONE, t.badge);
        }
    }

    /** Digital volume below 0 dB (not locked) changes the samples, so it is not bit-perfect. */
    @Test public void loweredVolumeIsNotBitPerfect() throws Exception {
        try (InputStream in = TrackTest.class.getResourceAsStream("/fixtures/wav192.json")) {
            JSONObject j = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            JSONObject v = j.getJSONObject("volumeData");
            v.put("display", "-12 dB");
            v.put("isLock", false);
            v.put("currenttVolume", 150);
            Track t = Track.from(Flat.of(j), true);
            assertEquals(Track.Badge.VOLUME, t.badge);
            assertEquals("Volume −12 dB", t.badgeText);
        }
    }

    @Test public void formatShortAndBitrate() throws Exception {
        Track t = load("wav192");
        assertEquals("24bit/192kHz", t.formatShort);
        assertEquals(9.22e6, t.bitrateBps, 1);
        Track d = load("dsd256");
        assertEquals("DSD256/11.29MHz", d.formatShort);
        Track m = load("mp3");
        assertEquals(128000, m.bitrateBps, 1);   // the mp3 fixture is a 128 kbps track (Venus)
    }

    @Test public void albumTagsAreSplitOut() {
        Track t = new Track();
        Track.splitAlbum(t, "Aja (1977 Original Master · Analogue Productions SACD · DSD64)");
        assertEquals("Aja", t.album);
        assertEquals(3, t.tags.size());
    }

    @Test public void rateText() {
        assertEquals("44.1 kHz", Track.rateText(44100.0, false));
        assertEquals("176.4 kHz", Track.rateText(176400.0, false));
        assertEquals("192 kHz", Track.rateText(192000.0, false));
        assertEquals("N/A", Track.rateText(null, false));
    }

    @Test public void rateCompact() {
        assertEquals("44.1kHz", Track.rateCompact(44100.0));
        assertEquals("192kHz", Track.rateCompact(192000.0));
        assertEquals("11.29MHz", Track.rateCompact(11289600.0));
    }

    /** Output settings page: only plain get-addresses are ever called. */
    @Test public void outputOptionsAddressIsGetOnly() {
        assertEquals("/SystemSettings/audioSettings/getUsbDacOutputOption",
            Api.outputOptions("/SystemSettings/audioSettings/getUsbDacOutputOption"));
        assertNull(Api.outputOptions("/SystemSettings/audioSettings/xlrOutputOption/setPhoneAudioDPLLBandwidth?index=%s&type=%s"));
        assertNull(Api.outputOptions("/SystemSettings/audioSettings/setSomething"));
        assertNull(Api.outputOptions("http://evil/SystemSettings/x/getY"));
        assertNull(Api.outputOptions(null));
    }

    /** Values from the three control shapes measured on the A6 (USB DAC + XLR/RCA pages, 2026-11-15). */
    @Test public void outputSettingsValues() throws Exception {
        JSONObject reply = new JSONObject("{\"status\":200,\"items\":["
            + "{\"title\":\"DAC filter characteristics\",\"option\":1,\"selectTitle\":\"Fast Roll-Off(Linear)\"},"
            + "{\"title\":\"Sample rate switch latency\",\"option\":7,\"currentTitle\":\"0 ms\",\"selectTitle\":\"0 ms\"},"
            + "{\"title\":\"DPLL Bandwidth\",\"option\":3,\"selectTitle\":null,"
            + "\"pcmObj\":{\"currentTitle\":\"PCM: 10\"},\"dsdObj\":{\"currentTitle\":\"DSD: 13\"}},"
            + "{\"title\":\"Something new\",\"option\":9}]}");
        java.util.List<String[]> s = Poller.outputSettings(reply);
        assertEquals(4, s.size());
        assertEquals("Fast Roll-Off(Linear)", s.get(0)[1]);
        assertEquals("0 ms", s.get(1)[1]);
        assertTrue(s.get(2)[1].contains("PCM: 10") && s.get(2)[1].contains("DSD: 13"));
        assertNull(s.get(3)[1]);
    }

    /** The signed token in a stream link is never shown. */
    @Test public void streamTokenIsHidden() {
        assertEquals("https://x.airable.io/tidal/play/abc (signed link hidden)",
            Poller.hideToken("https://x.airable.io/tidal/play/abc?sig=SECRET&exp=1"));
        assertEquals("/storage/FEFD-FF57/a.flac", Poller.hideToken("/storage/FEFD-FF57/a.flac"));
        assertNull(Poller.hideToken(null));
    }
}
