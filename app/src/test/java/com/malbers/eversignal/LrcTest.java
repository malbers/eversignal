package com.malbers.eversignal;

import static org.junit.Assert.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class LrcTest {
    @Test public void basicSyncedAndTags() {
        Lrc l = Lrc.parse("[ar:Artist]\n[ti:Title]\n[00:12.00]First line\n[00:15.50]Second line\n\n[01:02.3]Third");
        assertTrue(l.synced);
        assertEquals(3, l.lines.size());
        assertEquals(12000, l.lines.get(0).ms);
        assertEquals(15500, l.lines.get(1).ms);
        assertEquals(62300, l.lines.get(2).ms);
        assertEquals("Third", l.lines.get(2).text);
    }

    @Test public void multipleStampsOneLineSorted() {
        Lrc l = Lrc.parse("[00:30.00][00:10.00]Chorus\n[00:20.00]Verse");
        assertEquals(3, l.lines.size());
        assertEquals("Chorus", l.lines.get(0).text);
        assertEquals("Verse", l.lines.get(1).text);
        assertEquals(30000, l.lines.get(2).ms);
    }

    @Test public void offsetShiftsEarlier() {
        Lrc l = Lrc.parse("[offset:+500]\n[00:10.00]Line");
        assertEquals(9500, l.lines.get(0).ms);
        Lrc m = Lrc.parse("[offset:-250]\n[00:10.00]Line");
        assertEquals(10250, m.lines.get(0).ms);
    }

    @Test public void threeDigitFractionAndColonFraction() {
        assertEquals(1234, Lrc.parse("[00:01.234]x").lines.get(0).ms);
        assertEquals(1500, Lrc.parse("[00:01:50]x").lines.get(0).ms);
    }

    @Test public void enhancedWordStampsStripped() {
        assertEquals("Hello world", Lrc.parse("[00:01.00]<00:01.00>Hello <00:01.50>world").lines.get(0).text);
    }

    @Test public void indexAt() {
        Lrc l = Lrc.parse("[00:05.00]a\n[00:10.00]b\n[00:20.00]c");
        assertEquals(-1, l.indexAt(4999));
        assertEquals(0, l.indexAt(5000));
        assertEquals(1, l.indexAt(19999));
        assertEquals(2, l.indexAt(999_999));
    }

    @Test public void unsyncedPlainLyrics() {
        Lrc l = Lrc.parse("Just words\nNo stamps here");
        assertFalse(l.synced);
        assertEquals(2, l.lines.size());
        assertEquals(-1, l.indexAt(5000));
    }

    @Test public void emptyIsNull() {
        assertNull(Lrc.parse(""));
        assertNull(Lrc.parse("[ar:Only tags]\n[ti:Nothing]"));
        assertNull(Lrc.parse(null));
    }

    /** Pierre's lyrics are often French: UTF-8 with BOM, UTF-8 plain, and Windows-1252 must all come out right. */
    @Test public void decodeEncodings() {
        String fr = "[00:01.00]Ça déménage, où est l'été ?";
        byte[] bom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, fr.getBytes(StandardCharsets.UTF_8));
        assertEquals("Ça déménage, où est l'été ?", Lrc.parse(Lrc.decode(bom)).lines.get(0).text);
        assertEquals("Ça déménage, où est l'été ?", Lrc.parse(Lrc.decode(fr.getBytes(StandardCharsets.UTF_8))).lines.get(0).text);
        assertEquals("Ça déménage, où est l'été ?", Lrc.parse(Lrc.decode(fr.getBytes(Charset.forName("windows-1252")))).lines.get(0).text);
        byte[] u16 = concat(new byte[]{(byte) 0xFF, (byte) 0xFE}, fr.getBytes(StandardCharsets.UTF_16LE));
        assertEquals("Ça déménage, où est l'été ?", Lrc.parse(Lrc.decode(u16)).lines.get(0).text);
    }

    @Test public void lrcName() {
        assertEquals("01 Song.lrc", Lrc.lrcNameFor("01 Song.flac"));
        assertEquals("a.b.lrc", Lrc.lrcNameFor("a.b.dsf"));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }
}
