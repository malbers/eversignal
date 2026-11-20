package com.malbers.eversignal;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.0.12: lyrics from an .LRC file next to the audio file (pure Java).
 *
 * Pierre C. (2026-11-20) keeps "<track name>.lrc" beside every track; the Eversolo mobile app scrolls it on its
 * third screen, small. EverSignal shows it large in the full-screen view. Format: "[mm:ss.xx] line", several
 * stamps may share one line, "[offset:+/-ms]" shifts all of them, other [tags] are metadata. A file with no
 * stamps at all is plain (unsynced) lyrics and is shown as text that scrolls with the track's progress.
 */
final class Lrc {
    static final class Line {
        final long ms;
        final String text;
        Line(long ms, String text) { this.ms = ms; this.text = text; }
    }

    final List<Line> lines;
    final boolean synced;

    private Lrc(List<Line> lines, boolean synced) { this.lines = lines; this.synced = synced; }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern TAG = Pattern.compile("^\\[([a-zA-Z]+):([^\\]]*)\\]\\s*$");

    /** Null when there is nothing to show. */
    static Lrc parse(String text) {
        if (text == null) return null;
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);
        long offset = 0;
        List<Line> timed = new ArrayList<>(), plain = new ArrayList<>();
        for (String raw : text.split("\\r?\\n|\\r")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            Matcher tag = TAG.matcher(s);
            if (tag.matches() && !STAMP.matcher(s).lookingAt()) {
                if (tag.group(1).toLowerCase(Locale.ROOT).equals("offset")) {
                    try { offset = Long.parseLong(tag.group(2).trim().replace("+", "")); } catch (NumberFormatException ignored) { }
                }
                continue;
            }
            Matcher m = STAMP.matcher(s);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000;
                String frac = m.group(3);
                if (frac != null) ms += frac.length() == 1 ? Long.parseLong(frac) * 100 : frac.length() == 2 ? Long.parseLong(frac) * 10 : Long.parseLong(frac);
                stamps.add(ms);
                end = m.end();
            }
            String words = s.substring(end).replaceAll("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>", "").trim();   // enhanced LRC word stamps
            if (stamps.isEmpty()) plain.add(new Line(-1, s));
            else for (long ms : stamps) timed.add(new Line(ms, words));
        }
        if (!timed.isEmpty()) {
            List<Line> out = new ArrayList<>();
            // offset: positive = lyrics appear EARLIER (the LRC convention)
            for (Line l : timed) out.add(new Line(Math.max(0, l.ms - offset), l.text));
            Collections.sort(out, (a, b) -> Long.compare(a.ms, b.ms));
            return new Lrc(out, true);
        }
        return plain.isEmpty() ? null : new Lrc(plain, false);
    }

    /** The line being sung at ms, or -1 before the first stamp (synced files only). */
    int indexAt(long ms) {
        if (!synced) return -1;
        int lo = 0, hi = lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).ms <= ms) { found = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return found;
    }

    /**
     * Text from an .lrc file. UTF-8 (with or without BOM) when it decodes cleanly, UTF-16 with a BOM, otherwise
     * Windows-1252, which is what older lyric tools write for French and other Western accents.
     */
    static String decode(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }

    /** "01 Song.flac" → candidate lyric file names, in the order to try. */
    static String lrcNameFor(String audioName) {
        int d = audioName.lastIndexOf('.');
        return (d > 0 ? audioName.substring(0, d) : audioName) + ".lrc";
    }
}
