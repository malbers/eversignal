package com.malbers.eversignal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.0.12: pictures embedded in an audio file (pure Java, no Android), read straight from the file on the player.
 *
 * Covers the containers Eversolo owners rip to and tag: FLAC PICTURE blocks; ID3v2 APIC/PIC in MP3, in WAV and AIFF
 * ("id3 " / "ID3 " chunks, which is how Mp3tag tags WAV) and in DSF (the metadata pointer at offset 20); MP4/M4A
 * "covr". A file can hold several pictures (front, back, booklet), and all are returned, front cover first.
 *
 * The format is detected from the first bytes, not the extension. Read-only: the file is opened for reading
 * and nothing is ever written. Anything malformed ends the scan quietly with what was found so far.
 */
final class EmbeddedArt {
    private EmbeddedArt() {}

    /** Random-access reads over a file (or a byte array in tests). */
    interface Src {
        long length() throws IOException;
        /** Reads up to len bytes at pos; returns the count read, or -1 at the end. */
        int read(long pos, byte[] b, int off, int len) throws IOException;
    }

    static final class Pic {
        final int type;          // ID3/FLAC picture type: 3 = front cover, 4 = back, 0 = other ...
        final String mime;
        final byte[] data;
        Pic(int type, String mime, byte[] data) { this.type = type; this.mime = mime; this.data = data; }
    }

    /** One embedded picture larger than this is skipped (a cover is rarely over 5 MB). */
    static final int MAX_PIC = 16 * 1024 * 1024;
    /** Stop collecting once this much picture data is held. */
    static final int MAX_TOTAL = 40 * 1024 * 1024;

    static List<Pic> read(Src src) {
        List<Pic> out = new ArrayList<>();
        try {
            byte[] h = bytes(src, 0, 12);
            if (h == null) return out;
            if (is(h, 0, "fLaC")) flac(src, 4, out);
            else if (is(h, 0, "ID3")) {
                long end = id3(src, 0, out);
                byte[] f = bytes(src, end, 4);
                if (f != null && is(f, 0, "fLaC")) flac(src, end + 4, out);   // ID3 in front of a FLAC stream
            }
            else if (is(h, 0, "RIFF") && is(h, 8, "WAVE")) riff(src, 12, false, out);
            else if (is(h, 0, "FORM") && (is(h, 8, "AIFF") || is(h, 8, "AIFC"))) riff(src, 12, true, out);
            else if (is(h, 0, "DSD ")) {
                byte[] p = bytes(src, 20, 8);
                long meta = p == null ? 0 : le(p, 0, 8);
                if (meta > 0 && meta < src.length()) id3(src, meta, out);
            }
            else if (is(h, 4, "ftyp")) mp4(src, 0, src.length(), 0, out);
        } catch (IOException | RuntimeException ignored) {
            // malformed or unreadable: keep what was found
        }
        // front cover first, then the rest in file order
        List<Pic> sorted = new ArrayList<>();
        for (Pic p : out) if (p.type == 3) sorted.add(p);
        for (Pic p : out) if (p.type != 3) sorted.add(p);
        return sorted;
    }

    // ---------------------------------------------------------------- FLAC

    private static void flac(Src src, long pos, List<Pic> out) throws IOException {
        for (int guard = 0; guard < 128; guard++) {
            byte[] bh = bytes(src, pos, 4);
            if (bh == null) return;
            boolean last = (bh[0] & 0x80) != 0;
            int type = bh[0] & 0x7F;
            long len = be(bh, 1, 3);
            if (type == 6 && len <= MAX_PIC + 4096) {
                byte[] b = bytes(src, pos + 4, (int) len);
                if (b != null) {
                    int i = 0;
                    int ptype = (int) be(b, i, 4); i += 4;
                    int ml = (int) be(b, i, 4); i += 4;
                    String mime = new String(b, i, ml, StandardCharsets.US_ASCII); i += ml;
                    int dl = (int) be(b, i, 4); i += 4 + dl;
                    i += 16;                                  // width, height, depth, colours
                    int n = (int) be(b, i, 4); i += 4;
                    if (n > 0 && i + n <= b.length) add(out, ptype, mime, java.util.Arrays.copyOfRange(b, i, i + n));
                }
            }
            if (last || type == 127) return;
            pos += 4 + len;
        }
    }

    // ---------------------------------------------------------------- ID3v2

    /** Parses an ID3v2 tag at pos; returns the position just after it. */
    static long id3(Src src, long pos, List<Pic> out) throws IOException {
        byte[] h = bytes(src, pos, 10);
        if (h == null || !is(h, 0, "ID3")) return pos;
        int ver = h[3] & 0xFF, flags = h[5] & 0xFF;
        long size = syncsafe(h, 6);
        long end = pos + 10 + size + ((flags & 0x10) != 0 ? 10 : 0);
        if (size <= 0 || size > 64L * 1024 * 1024) return end;
        byte[] t = bytes(src, pos + 10, (int) size);
        if (t == null) return end;
        if (ver < 4 && (flags & 0x80) != 0) t = unsync(t, 0, t.length);   // v2.2/2.3 whole-tag unsynchronisation
        int i = 0;
        if (ver == 3 && (flags & 0x40) != 0) i += 4 + (int) be(t, 0, 4);  // extended header
        if (ver == 4 && (flags & 0x40) != 0) i += (int) syncsafe(t, 0);
        int idLen = ver == 2 ? 3 : 4, hdr = ver == 2 ? 6 : 10;
        while (i + hdr <= t.length && t[i] != 0) {
            String id = new String(t, i, idLen, StandardCharsets.US_ASCII);
            long fs = ver == 2 ? be(t, i + 3, 3) : ver == 4 ? syncsafe(t, i + 4) : be(t, i + 4, 4);
            int fflags = ver == 2 ? 0 : (t[i + 9] & 0xFF);
            int start = i + hdr;
            if (fs <= 0 || start + fs > t.length) break;
            if (id.equals("APIC") || id.equals("PIC")) {
                byte[] f = java.util.Arrays.copyOfRange(t, start, start + (int) fs);
                if (ver == 4) {
                    if ((fflags & 0x01) != 0 && f.length > 4) f = java.util.Arrays.copyOfRange(f, 4, f.length); // data length indicator
                    if ((fflags & 0x02) != 0) f = unsync(f, 0, f.length);
                }
                apic(f, ver == 2, out);
            }
            i = start + (int) fs;
        }
        return end;
    }

    private static void apic(byte[] f, boolean v22, List<Pic> out) {
        if (f.length < 4) return;
        int enc = f[0] & 0xFF, i = 1;
        String mime;
        if (v22) { mime = "image/" + new String(f, 1, 3, StandardCharsets.US_ASCII).trim().toLowerCase(java.util.Locale.ROOT); i = 4; }
        else {
            int z = i;
            while (z < f.length && f[z] != 0) z++;
            mime = new String(f, i, z - i, StandardCharsets.ISO_8859_1);
            i = z + 1;
        }
        if (i >= f.length) return;
        int ptype = f[i++] & 0xFF;
        // description, null-terminated: one zero byte for Latin-1/UTF-8, two (aligned) for UTF-16
        if (enc == 1 || enc == 2) {
            while (i + 1 < f.length && !(f[i] == 0 && f[i + 1] == 0)) i += 2;
            i += 2;
        } else {
            while (i < f.length && f[i] != 0) i++;
            i += 1;
        }
        if (i < f.length) add(out, ptype, mime, java.util.Arrays.copyOfRange(f, i, f.length));
    }

    private static byte[] unsync(byte[] b, int off, int len) {
        byte[] o = new byte[len];
        int n = 0;
        for (int i = off; i < off + len; i++) {
            o[n++] = b[i];
            if ((b[i] & 0xFF) == 0xFF && i + 1 < off + len && b[i + 1] == 0) i++;
        }
        return java.util.Arrays.copyOf(o, n);
    }

    // ---------------------------------------------------------------- WAV / AIFF

    private static void riff(Src src, long pos, boolean bigEndian, List<Pic> out) throws IOException {
        long len = src.length();
        for (int guard = 0; guard < 512 && pos + 8 <= len; guard++) {
            byte[] ch = bytes(src, pos, 8);
            if (ch == null) return;
            String id = new String(ch, 0, 4, StandardCharsets.US_ASCII);
            long size = bigEndian ? be(ch, 4, 4) : le(ch, 4, 4);
            if (id.equalsIgnoreCase("id3 ")) id3(src, pos + 8, out);
            pos += 8 + size + (size & 1);
        }
    }

    // ---------------------------------------------------------------- MP4 / M4A

    private static void mp4(Src src, long pos, long end, int depth, List<Pic> out) throws IOException {
        if (depth > 8) return;
        for (int guard = 0; guard < 4096 && pos + 8 <= end; guard++) {
            byte[] a = bytes(src, pos, 16);
            if (a == null) return;
            long size = be(a, 0, 4);
            String type = new String(a, 4, 4, StandardCharsets.ISO_8859_1);
            int hdr = 8;
            if (size == 1) { size = be(a, 8, 8); hdr = 16; }
            else if (size == 0) size = end - pos;
            if (size < hdr || pos + size > end) return;
            switch (type) {
                case "moov": case "udta": case "ilst": case "covr":
                    mp4(src, pos + hdr, pos + size, depth + 1, out); break;
                case "meta":
                    mp4(src, pos + hdr + 4, pos + size, depth + 1, out); break;   // version + flags
                case "data":
                    long n = size - hdr - 8;
                    if (n > 0 && n <= MAX_PIC) {
                        byte[] d = bytes(src, pos + hdr, (int) (size - hdr));
                        if (d != null) {
                            int kind = (int) be(d, 0, 4) & 0xFFFFFF;
                            String mime = kind == 14 ? "image/png" : "image/jpeg";
                            add(out, out.isEmpty() ? 3 : 0, mime, java.util.Arrays.copyOfRange(d, 8, d.length));
                        }
                    }
                    break;
                default: break;
            }
            pos += size;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void add(List<Pic> out, int type, String mime, byte[] data) {
        if (data.length < 64 || data.length > MAX_PIC) return;
        long total = 0;
        for (Pic p : out) total += p.data.length;
        if (total + data.length > MAX_TOTAL) return;
        out.add(new Pic(type, mime, data));
    }

    static byte[] bytes(Src src, long pos, int n) throws IOException {
        if (n < 0 || pos < 0 || pos + n > src.length()) return null;
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = src.read(pos + got, b, got, n - got);
            if (r <= 0) return null;
            got += r;
        }
        return b;
    }

    private static boolean is(byte[] b, int off, String s) {
        if (off + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) if (b[off + i] != (byte) s.charAt(i)) return false;
        return true;
    }

    static long be(byte[] b, int off, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    static long le(byte[] b, int off, int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    private static long syncsafe(byte[] b, int off) {
        return ((b[off] & 0x7FL) << 21) | ((b[off + 1] & 0x7FL) << 14) | ((b[off + 2] & 0x7FL) << 7) | (b[off + 3] & 0x7FL);
    }

    /** A Src over a byte array (tests). */
    static Src of(byte[] data) {
        return new Src() {
            @Override public long length() { return data.length; }
            @Override public int read(long pos, byte[] b, int off, int len) {
                if (pos >= data.length) return -1;
                int n = (int) Math.min(len, data.length - pos);
                System.arraycopy(data, (int) pos, b, off, n);
                return n;
            }
        };
    }
}
