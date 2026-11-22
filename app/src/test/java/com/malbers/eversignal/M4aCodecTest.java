package com.malbers.eversignal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** 1.0.12.7: the .m4a codec comes from the file header, and the badge follows it (Dolby Atmos files, 2026-11-22). */
public class M4aCodecTest {

    private static byte[] atom(String type, byte[]... kids) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] k : kids) b.write(k, 0, k.length);
        byte[] body = b.toByteArray();
        ByteBuffer bb = ByteBuffer.allocate(8 + body.length);
        bb.putInt(8 + body.length).put(type.getBytes(StandardCharsets.ISO_8859_1)).put(body);
        return bb.array();
    }

    private static byte[] stsd(String fmt) {
        ByteBuffer e = ByteBuffer.allocate(16);
        e.putInt(16).put(fmt.getBytes(StandardCharsets.ISO_8859_1)).putInt(0).putInt(1);
        ByteBuffer h = ByteBuffer.allocate(8);
        h.putInt(0).putInt(1);
        return atom("stsd", h.array(), e.array());
    }

    private static byte[] trak(String fmt) {
        return atom("trak", atom("mdia", atom("minf", atom("stbl", stsd(fmt)))));
    }

    private static File file(byte[]... parts) throws Exception {
        File f = File.createTempFile("es-m4a", ".m4a");
        f.deleteOnExit();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p, 0, p.length);
        Files.write(f.toPath(), b.toByteArray());
        return f;
    }

    private static final byte[] FTYP = atom("ftyp", "M4A ".getBytes(StandardCharsets.ISO_8859_1));

    @Test public void readsEac3() throws Exception {
        assertEquals("ec-3", M4aCodec.sniff(file(FTYP, atom("moov", trak("ec-3")), atom("mdat", new byte[64]))));
    }

    @Test public void readsAlacWithMoovAtTheEnd() throws Exception {
        assertEquals("alac", M4aCodec.sniff(file(FTYP, atom("mdat", new byte[4096]), atom("moov", trak("alac")))));
    }

    @Test public void skipsAVideoTrak() throws Exception {
        assertEquals("mp4a", M4aCodec.sniff(file(FTYP, atom("moov", trak("avc1"), trak("mp4a")))));
    }

    /** The dec3 box of the real BINI "Unang Kilig" Atmos file (Apple Music, 768 kbps), 2026-11-22: 5.1 + Atmos. */
    @Test public void realAtmosHeader() {
        byte[] dec3 = {0x18, 0x00, 0x20, 0x0f, 0x00, 0x01, 0x10};
        M4aCodec.Info i = M4aCodec.dec3(dec3);
        assertEquals(6.0, i.channels, 0.01);
        assertTrue(i.atmos);
        assertFalse(M4aCodec.dec3(new byte[]{0x18, 0x00, 0x20, 0x0f, 0x00}).atmos);   // no extension byte: plain E-AC-3

        Track t = dolbyAsReported(16, 2, "917.86 Kbps");   // the Library's 16-bit / 2 ch
        t.applyM4aCodec(i.codec, i.channels, i.atmos, true);
        assertEquals(6.0, t.channels, 0.01);
        assertTrue(t.badgeNote.startsWith("E-AC-3 · Dolby Atmos"));
        assertEquals(Track.Badge.LOSSY, t.badge);
    }

    @Test public void garbageIsNull() throws Exception {
        assertNull(M4aCodec.sniff(file("not an mp4 at all".getBytes(StandardCharsets.US_ASCII))));
        assertNull(M4aCodec.sniff(new File("/no/such/file.m4a")));
    }

    /** What the A6 reported for the Atmos files: file browser 32-bit/6 ch, Library 16-bit/2 ch. Both badged Bit-perfect before. */
    private static Track dolbyAsReported(double bits, double ch, String kbps) {
        Track t = new Track();
        t.ownPlayer = true; t.container = "M4A"; t.rateHz = 48000.0; t.bits = bits; t.channels = ch; t.bitrate = kbps;
        t.outCodec = "PCM"; t.outRateHz = 48000.0; t.volumeOk = true; t.volume = "0dB";
        // the 1.0.8 rule, as Track.from applies it
        t.container = "ALAC"; t.lossy = false; t.m4a = true; t.codecUnknown = true;
        t.computeBadge(true);
        return t;
    }

    @Test public void dolbyIsNeverBitPerfect() {
        for (Track t : new Track[]{dolbyAsReported(32, 6, "876.71 Kbps"), dolbyAsReported(16, 2, "917.86 Kbps")}) {
            assertEquals(Track.Badge.UNVERIFIED, t.badge);   // codec not read yet: no verdict
            t.applyM4aCodec("ec-3", true);
            assertEquals(Track.Badge.LOSSY, t.badge);
            assertEquals("E-AC-3", t.container);
            assertTrue(t.lossy && t.dolby);
            assertNull(t.bits);
            assertNull(t.channels);   // codec only (no header channels): never the player's count
        }
    }

    @Test public void realAlacStaysBitPerfect() {
        Track t = new Track();
        t.ownPlayer = true; t.rateHz = 96000.0; t.bits = 24.0; t.channels = 2.0; t.outCodec = "PCM"; t.outRateHz = 96000.0;
        t.volumeOk = true; t.volume = "0dB"; t.container = "ALAC"; t.m4a = true; t.codecUnknown = true;
        t.computeBadge(true);
        assertEquals(Track.Badge.UNVERIFIED, t.badge);
        t.applyM4aCodec("alac", true);
        assertEquals(Track.Badge.BIT_PERFECT, t.badge);
        assertFalse(t.lossy);
    }

    @Test public void aacM4aIsLossy() {
        Track t = dolbyAsReported(16, 2, "265 Kbps");
        t.applyM4aCodec("mp4a", true);
        assertEquals(Track.Badge.LOSSY, t.badge);
        assertEquals("AAC", t.container);
    }
}
