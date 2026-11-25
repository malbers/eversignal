package com.malbers.eversignal;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 1.0.12.7: which codec an .m4a really holds, read from the file itself (the MP4 sample description, "stsd").
 *
 * Why: the Eversolo names no codec for .m4a, and its numbers can't be trusted for one. Measured 2026-11-22 on the
 * A6 Gen 1, Dolby Atmos (E-AC-3 JOC, 5.1, 768 kbps) files: started from the file browser the player said
 * 32-bit / 6 ch / 877 kbps, from the Library 16-bit / 2 ch / 918 kbps. Both passed the 1.0.8 "ALAC" test, and
 * EverSignal showed Bit-perfect (reported by Pierre C.). The file header says "ec-3" either way.
 *
 * Reads only box headers (a few KB, wherever "moov" sits in the file). Never throws; null = couldn't tell.
 */
final class M4aCodec {
    private M4aCodec() {}

    static boolean isM4a(String path) {
        if (path == null) return false;
        String p = path.toLowerCase(java.util.Locale.US);
        return p.endsWith(".m4a") || p.endsWith(".mp4") || p.endsWith(".m4b");
    }

    /** What the header says. channels = null when not read (only Dolby is parsed: the player's count is unreliable there). */
    static final class Info {
        final String codec;      // "alac", "mp4a" (AAC), "ec-3", "ac-3", "ac-4", "flac", "opus"
        final Double channels;   // 1.0.12.8: from dec3/dac3, e.g. 6 for 5.1
        final boolean atmos;     // 1.0.12.8: E-AC-3 with the JOC (Atmos) extension flag
        final Double rate;       // 1.0.14: ALAC only, from its config box (the player reports some ALAC as "0.001 kHz")
        Info(String codec, Double channels, boolean atmos) { this(codec, channels, atmos, null); }
        Info(String codec, Double channels, boolean atmos, Double rate) {
            this.codec = codec; this.channels = channels; this.atmos = atmos; this.rate = rate;
        }
    }

    /** The codec only ("alac", "mp4a", "ec-3", ...), or null. */
    static String sniff(File f) {
        Info i = read(f);
        return i == null ? null : i.codec;
    }

    static Info read(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            return find(raf, 0, raf.length(), 0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static final String[] PATH = {"moov", "trak", "mdia", "minf", "stbl", "stsd"};
    private static final int MAX_BOXES = 4000;

    /** Walks the boxes in [start, end) looking for PATH[depth]; at stsd returns the first audio sample entry. */
    private static Info find(RandomAccessFile raf, long start, long end, int depth) throws IOException {
        long pos = start;
        int n = 0;
        while (pos + 8 <= end && n++ < MAX_BOXES) {
            raf.seek(pos);
            long size = raf.readInt() & 0xFFFFFFFFL;
            String type = fourcc(raf);
            int header = 8;
            if (size == 1) { size = raf.readLong(); header = 16; }
            else if (size == 0) size = end - pos;
            if (size < header || pos + size > end) return null;
            if (type.equals(PATH[depth])) {
                if (depth == PATH.length - 1) return firstEntry(raf, pos + header, pos + size);
                Info r = find(raf, pos + header, pos + size, depth + 1);
                if (r != null) return r;   // a video or text trak: keep looking at the next trak
            }
            pos += size;
        }
        return null;
    }

    private static Info firstEntry(RandomAccessFile raf, long start, long end) throws IOException {
        // stsd: version/flags (4) + entry count (4), then entries: size (4) + format (4)
        if (start + 16 > end) return null;
        long entry = start + 8;
        raf.seek(entry);
        long entrySize = raf.readInt() & 0xFFFFFFFFL;
        String fmt = fourcc(raf).toLowerCase(java.util.Locale.US);
        switch (fmt) {
            case "alac": {
                // ALACSpecificConfig after version/flags: frameLength 4, compatibleVersion 1, bitDepth 1, pb 1, mb 1,
                // kb 1, numChannels 1, maxRun 2, maxFrameBytes 4, avgBitRate 4, sampleRate 4 (Apple ALAC spec)
                long kids = entry + 36, kidsEnd = Math.min(end, entry + entrySize);
                return new Info(fmt, null, false, alacRate(child(raf, kids, kidsEnd, "alac")));
            }
            case "mp4a": case "ac-4": case "flac": case "opus": case "fla1":
                return new Info(fmt, null, false);
            case "ec-3": case "ac-3": {
                // AudioSampleEntry: 8 header + 28 fields, then child boxes (dec3 / dac3)
                long kids = entry + 36, kidsEnd = Math.min(end, entry + entrySize);
                byte[] cfg = child(raf, kids, kidsEnd, fmt.equals("ec-3") ? "dec3" : "dac3");
                if (cfg == null) return new Info(fmt, null, false);
                return fmt.equals("ec-3") ? dec3(cfg) : dac3(cfg);
            }
            default:
                return null;   // not audio (avc1, text...)
        }
    }

    private static byte[] child(RandomAccessFile raf, long pos, long end, String want) throws IOException {
        int n = 0;
        while (pos + 8 <= end && n++ < 64) {
            raf.seek(pos);
            long size = raf.readInt() & 0xFFFFFFFFL;
            String type = fourcc(raf);
            if (size < 8 || pos + size > end) return null;
            if (type.equals(want)) {
                int len = (int) Math.min(size - 8, 64);
                byte[] b = new byte[len];
                raf.readFully(b);
                return b;
            }
            pos += size;
        }
        return null;
    }

    /** 1.0.14: the sample rate in an ALAC config box (body after the 8-byte header), or null. */
    static Double alacRate(byte[] b) {
        if (b == null || b.length < 28) return null;
        long r = ((b[24] & 0xFFL) << 24) | ((b[25] & 0xFFL) << 16) | ((b[26] & 0xFFL) << 8) | (b[27] & 0xFFL);
        return r >= 8000 && r <= 768000 ? (double) r : null;
    }

    /** acmod -> full-range channels (ETSI TS 102 366 table 4.3). */
    private static final int[] ACMOD = {2, 1, 2, 3, 3, 4, 4, 5};

    /** ac-3: fscod(2) bsid(5) bsmod(3) acmod(3) lfeon(1). */
    static Info dac3(byte[] b) {
        Bits r = new Bits(b);
        r.skip(2 + 5 + 3);
        int acmod = r.get(3), lfe = r.get(1);
        return r.ok() ? new Info("ac-3", (double) (ACMOD[acmod] + lfe), false) : new Info("ac-3", null, false);
    }

    /**
     * ec-3 (ETSI TS 102 366 annex F.6): data_rate(13) num_ind_sub(3), then per independent substream
     * fscod(2) bsid(5) reserved(1) asvc(1) bsmod(3) acmod(3) lfeon(1) reserved(3) num_dep_sub(4) and
     * chan_loc(9) or reserved(1). After them, optionally reserved(7) flag_ec3_extension_type_a(1): Atmos (JOC).
     */
    static Info dec3(byte[] b) {
        Bits r = new Bits(b);
        r.skip(13);
        int subs = r.get(3) + 1;
        int ch = 0;
        for (int i = 0; i < subs && r.ok(); i++) {
            r.skip(2 + 5 + 1 + 1 + 3);
            int acmod = r.get(3), lfe = r.get(1);
            r.skip(3);
            int dep = r.get(4);
            int loc = dep > 0 ? r.get(9) : (r.skip(1) ? 0 : 0);
            if (i == 0) ch = ACMOD[acmod] + lfe + extra(loc);
        }
        if (!r.ok()) return new Info("ec-3", null, false);
        boolean atmos = false;
        if (r.left() >= 16) { r.skip(7); atmos = r.get(1) == 1; }
        return new Info("ec-3", (double) ch, atmos);
    }

    /** chan_loc bits (MSB first): Lc/Rc, Lrs/Rrs, Cs, Ts, Lsd/Rsd, Lw/Rw, Lvh/Rvh, Cvh, LFE2. */
    private static int extra(int loc) {
        int[] w = {2, 2, 1, 1, 2, 2, 2, 1, 1};
        int n = 0;
        for (int i = 0; i < 9; i++) if ((loc >> (8 - i) & 1) == 1) n += w[i];
        return n;
    }

    /** MSB-first bit reader that never throws; ok() turns false on overrun. */
    private static final class Bits {
        private final byte[] b; private int pos; private boolean over;
        Bits(byte[] b) { this.b = b; }
        int get(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                int byteIdx = pos >> 3;
                if (byteIdx >= b.length) { over = true; return 0; }
                v = (v << 1) | ((b[byteIdx] >> (7 - (pos & 7))) & 1);
                pos++;
            }
            return v;
        }
        boolean skip(int n) { get(n); return true; }
        int left() { return b.length * 8 - pos; }
        boolean ok() { return !over; }
    }

    private static String fourcc(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return new String(b, StandardCharsets.ISO_8859_1);
    }
}
