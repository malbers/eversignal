package com.malbers.eversignal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DrMeterTest {

    private static DrMeter.Result run(int sr, int ch, double seconds, java.util.function.IntToDoubleFunction sample) {
        DrMeter m = new DrMeter(ch, sr);
        int frames = (int) (sr * seconds);
        float[] buf = new float[4096 * ch];
        int k = 0;
        for (int i = 0; i < frames; i++) {
            double v = sample.applyAsDouble(i);
            for (int c = 0; c < ch; c++) buf[k++] = (float) v;
            if (k == buf.length) { m.feed(buf, k); k = 0; }
        }
        m.feed(buf, k);
        return m.finish();
    }

    /** A pure sine has peak = sqrt(2) * RMS, which the meter's RMS definition cancels: DR0. */
    @Test public void sineIsDr0() {
        DrMeter.Result r = run(44100, 2, 30, i -> 0.5 * Math.sin(2 * Math.PI * 1000 * i / 44100.0));
        assertEquals(0, r.drRounded);
        assertEquals(0.0, r.dr, 0.05);
        assertEquals(-6.02, r.peakDb, 0.05);
    }

    /** Quiet sine (0.25) with one full-scale spike per block: DR = 20 log10(1 / 0.25) = 12.04. */
    @Test public void spikesOverQuietSineIsDr12() {
        int sr = 48000;
        DrMeter.Result r = run(sr, 2, 60, i -> (i % (sr * 3)) == 1000 ? 1.0 : 0.25 * Math.sin(2 * Math.PI * 440 * i / (double) sr));
        assertEquals(12, r.drRounded);
        assertEquals(12.04, r.dr, 0.1);
        assertEquals(20, r.blocks);
    }

    /** Only the loudest 20% of blocks set the RMS: a quiet half doesn't raise DR. */
    @Test public void quietPassagesDontCount() {
        int sr = 44100;
        DrMeter.Result loudOnly = run(sr, 1, 30, i -> (i % (sr * 3)) == 7 ? 1.0 : 0.25 * Math.sin(2 * Math.PI * 440 * i / (double) sr));
        DrMeter.Result withQuiet = run(sr, 1, 60, i -> (i % (sr * 3)) == 7 ? 1.0
            : (i < sr * 30 ? 0.25 : 0.01) * Math.sin(2 * Math.PI * 440 * i / (double) sr));
        assertEquals(loudOnly.drRounded, withQuiet.drRounded);
    }

    @Test public void emptyIsNull() {
        assertNull(new DrMeter(2, 44100).finish());
        assertNotNull(run(44100, 2, 1, i -> 0.1));   // shorter than one block still measures
    }
}
