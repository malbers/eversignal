package com.malbers.eversignal;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

/** Builds tiny files in each container by hand and checks the pictures come back intact and in order. */
public class EmbeddedArtTest {

    private static byte[] img(int n, int seed) {
        byte[] b = new byte[n];
        b[0] = (byte) 0xFF; b[1] = (byte) 0xD8;           // JPEG-ish start
        for (int i = 2; i < n; i++) b[i] = (byte) (i * 31 + seed);
        return b;
    }

    private static void be(ByteArrayOutputStream o, long v, int n) { for (int i = n - 1; i >= 0; i--) o.write((int) (v >>> (8 * i)) & 0xFF); }
    private static void le(ByteArrayOutputStream o, long v, int n) { for (int i = 0; i < n; i++) o.write((int) (v >>> (8 * i)) & 0xFF); }
    private static void str(ByteArrayOutputStream o, String s) { o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1)); }
    private static void syncsafe(ByteArrayOutputStream o, int v) { o.write((v >> 21) & 0x7F); o.write((v >> 14) & 0x7F); o.write((v >> 7) & 0x7F); o.write(v & 0x7F); }

    private static byte[] flacPicture(int type, byte[] data) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        be(b, type, 4); be(b, 10, 4); str(b, "image/jpeg"); be(b, 0, 4);
        be(b, 500, 4); be(b, 500, 4); be(b, 24, 4); be(b, 0, 4);
        be(b, data.length, 4); b.writeBytes(data);
        return b.toByteArray();
    }

    @Test public void flacTwoPicturesFrontFirst() {
        byte[] back = img(300, 1), front = img(400, 2);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        str(f, "fLaC");
        f.write(0); be(f, 34, 3); f.writeBytes(new byte[34]);                 // STREAMINFO
        byte[] p1 = flacPicture(4, back), p2 = flacPicture(3, front);
        f.write(6); be(f, p1.length, 3); f.writeBytes(p1);
        f.write(0x80 | 6); be(f, p2.length, 3); f.writeBytes(p2);            // last block
        f.writeBytes(new byte[100]);                                         // audio frames
        List<EmbeddedArt.Pic> pics = EmbeddedArt.read(EmbeddedArt.of(f.toByteArray()));
        assertEquals(2, pics.size());
        assertEquals(3, pics.get(0).type);
        assertArrayEquals(front, pics.get(0).data);
        assertArrayEquals(back, pics.get(1).data);
    }

    private static byte[] id3v23(byte[] data, int ptype) {
        ByteArrayOutputStream fr = new ByteArrayOutputStream();
        fr.write(0); str(fr, "image/jpeg"); fr.write(0); fr.write(ptype); str(fr, "desc"); fr.write(0); fr.writeBytes(data);
        byte[] frame = fr.toByteArray();
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        str(t, "TIT2"); be(t, 6, 4); t.write(0); t.write(0); t.write(0); str(t, "Title");
        str(t, "APIC"); be(t, frame.length, 4); t.write(0); t.write(0); t.writeBytes(frame);
        t.writeBytes(new byte[20]);                                          // padding
        byte[] body = t.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        str(o, "ID3"); o.write(3); o.write(0); o.write(0); syncsafe(o, body.length); o.writeBytes(body);
        return o.toByteArray();
    }

    @Test public void mp3Id3v23Apic() {
        byte[] pic = img(500, 3);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.writeBytes(id3v23(pic, 3));
        f.writeBytes(new byte[200]);
        List<EmbeddedArt.Pic> pics = EmbeddedArt.read(EmbeddedArt.of(f.toByteArray()));
        assertEquals(1, pics.size());
        assertArrayEquals(pic, pics.get(0).data);
        assertEquals("image/jpeg", pics.get(0).mime);
    }

    @Test public void id3v24Utf16Description() {
        byte[] pic = img(500, 4);
        ByteArrayOutputStream fr = new ByteArrayOutputStream();
        fr.write(1); str(fr, "image/png"); fr.write(0); fr.write(3);
        fr.write(0xFF); fr.write(0xFE); fr.write('A'); fr.write(0); fr.write(0); fr.write(0);  // UTF-16 "A" + terminator
        fr.writeBytes(pic);
        byte[] frame = fr.toByteArray();
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        str(t, "APIC"); syncsafe(t, frame.length); t.write(0); t.write(0); t.writeBytes(frame);
        byte[] body = t.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        str(o, "ID3"); o.write(4); o.write(0); o.write(0); syncsafe(o, body.length); o.writeBytes(body);
        List<EmbeddedArt.Pic> pics = EmbeddedArt.read(EmbeddedArt.of(o.toByteArray()));
        assertEquals(1, pics.size());
        assertArrayEquals(pic, pics.get(0).data);
    }

    /** Mp3tag tags WAV with an "id3 " chunk (Václav's WAV albums). */
    @Test public void wavId3Chunk() {
        byte[] pic = img(600, 5);
        byte[] tag = id3v23(pic, 3);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        str(f, "RIFF"); le(f, 0, 4); str(f, "WAVE");
        str(f, "fmt "); le(f, 16, 4); f.writeBytes(new byte[16]);
        str(f, "data"); le(f, 9, 4); f.writeBytes(new byte[9]); f.write(0);    // odd size + pad byte
        str(f, "id3 "); le(f, tag.length, 4); f.writeBytes(tag);
        List<EmbeddedArt.Pic> pics = EmbeddedArt.read(EmbeddedArt.of(f.toByteArray()));
        assertEquals(1, pics.size());
        assertArrayEquals(pic, pics.get(0).data);
    }

    @Test public void dsfMetadataPointer() {
        byte[] pic = img(700, 6);
        byte[] tag = id3v23(pic, 3);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        str(f, "DSD "); le(f, 28, 8);
        long total = 28 + 60 + tag.length;
        le(f, total, 8); le(f, 28 + 60, 8);                                    // file size, metadata pointer
        f.writeBytes(new byte[60]);
        f.writeBytes(tag);
        List<EmbeddedArt.Pic> pics = EmbeddedArt.read(EmbeddedArt.of(f.toByteArray()));
        assertEquals(1, pics.size());
        assertArrayEquals(pic, pics.get(0).data);
    }

    private static byte[] atom(String type, byte[]... kids) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n = 8;
        for (byte[] k : kids) n += k.length;
        be(b, n, 4); str(b, type);
        for (byte[] k : kids) b.writeBytes(k);
        return b.toByteArray();
    }

    @Test public void m4aCovr() {
        byte[] pic = img(800, 7);
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        be(d, 13, 4); be(d, 0, 4); d.writeBytes(pic);                          // type 13 = JPEG, locale
        byte[] data = atom("data", d.toByteArray());
        byte[] meta = atom("meta", new byte[4], atom("ilst", atom("covr", data)));
        byte[] file = concat(atom("ftyp", "M4A ".getBytes(StandardCharsets.US_ASCII)), atom("moov", atom("udta", meta)), atom("mdat", new byte[50]));
        List<EmbeddedArt.Pic> pics = EmbeddedArt.read(EmbeddedArt.of(file));
        assertEquals(1, pics.size());
        assertArrayEquals(pic, pics.get(0).data);
    }

    @Test public void garbageAndTruncationAreQuiet() {
        assertTrue(EmbeddedArt.read(EmbeddedArt.of(new byte[0])).isEmpty());
        assertTrue(EmbeddedArt.read(EmbeddedArt.of("not audio at all".getBytes(StandardCharsets.US_ASCII))).isEmpty());
        byte[] tag = id3v23(img(500, 8), 3);
        byte[] cut = java.util.Arrays.copyOf(tag, tag.length / 2);
        assertTrue(EmbeddedArt.read(EmbeddedArt.of(cut)).isEmpty());
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.writeBytes(p);
        return b.toByteArray();
    }
}
