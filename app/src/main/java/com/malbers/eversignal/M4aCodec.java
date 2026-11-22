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

    /** "alac", "mp4a" (AAC), "ec-3", "ac-3", "ac-4", "flac", "opus", or null. */
    static String sniff(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            return find(raf, 0, raf.length(), 0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static final String[] PATH = {"moov", "trak", "mdia", "minf", "stbl", "stsd"};
    private static final int MAX_BOXES = 4000;

    /** Walks the boxes in [start, end) looking for PATH[depth]; at stsd returns the first audio sample entry. */
    private static String find(RandomAccessFile raf, long start, long end, int depth) throws IOException {
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
                String r = find(raf, pos + header, pos + size, depth + 1);
                if (r != null) return r;   // a video or text trak: keep looking at the next trak
            }
            pos += size;
        }
        return null;
    }

    private static String firstEntry(RandomAccessFile raf, long start, long end) throws IOException {
        // stsd: version/flags (4) + entry count (4), then entries: size (4) + format (4)
        if (start + 16 > end) return null;
        raf.seek(start + 8);
        raf.readInt();
        String fmt = fourcc(raf).toLowerCase(java.util.Locale.US);
        switch (fmt) {
            case "alac": case "mp4a": case "ec-3": case "ac-3": case "ac-4": case "flac": case "opus": case "fla1":
                return fmt;
            default:
                return null;   // not audio (avc1, text...)
        }
    }

    private static String fourcc(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return new String(b, StandardCharsets.ISO_8859_1);
    }
}
