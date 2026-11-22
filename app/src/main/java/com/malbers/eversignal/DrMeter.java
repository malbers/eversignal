package com.malbers.eversignal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dynamic Range (DR), the "TT DR Meter" method foobar2000's DR Meter uses (Pleasurize Music Foundation):
 *  - cut each channel into 3-second blocks;
 *  - per block: RMS (as sqrt(2 * mean square), so a full-scale sine reads 0 dB) and the peak;
 *  - RMS of the loudest 20% of blocks, and the SECOND-highest block peak;
 *  - DR per channel = 20 log10(peak2 / rmsTop20); the track's DR = the channel average, rounded.
 * Pure Java so it can be unit-tested off the device. Proven in EversoloStats 0.3.1 (matches foobar DR Meter 1.0.8 to 0.01 dB on the same file, 2026-11-22); in EverSignal from 1.0.12.12 (Pierre C.).
 */
public final class DrMeter {
    private final int channels, blockLen;
    private final double[] sum;      // per channel: sum of squares in the current block
    private final double[] peak;     // per channel: peak in the current block
    private final double[] trackPeak;
    private int inBlock;
    private final List<double[]> blockRms = new ArrayList<>();   // each entry: one value per channel
    private final List<double[]> blockPeak = new ArrayList<>();
    private long frames;

    public DrMeter(int channels, int sampleRate) {
        this.channels = Math.max(1, channels);
        this.blockLen = Math.max(1, sampleRate * 3);
        sum = new double[this.channels];
        peak = new double[this.channels];
        trackPeak = new double[this.channels];
    }

    /** Interleaved samples, scaled to [-1, 1]. count = number of samples (not frames). */
    public void feed(float[] x, int count) {
        int c = 0;
        for (int i = 0; i < count; i++) {
            double v = x[i];
            sum[c] += v * v;
            double a = Math.abs(v);
            if (a > peak[c]) peak[c] = a;
            if (a > trackPeak[c]) trackPeak[c] = a;
            if (++c == channels) {
                c = 0;
                frames++;
                if (++inBlock == blockLen) closeBlock();
            }
        }
    }

    private void closeBlock() {
        double[] r = new double[channels], p = new double[channels];
        for (int ch = 0; ch < channels; ch++) {
            r[ch] = Math.sqrt(2.0 * sum[ch] / inBlock);
            p[ch] = peak[ch];
            sum[ch] = 0; peak[ch] = 0;
        }
        blockRms.add(r); blockPeak.add(p);
        inBlock = 0;
    }

    public static final class Result {
        public double dr;            // channel average, unrounded
        public int drRounded;
        public double[] drPerChannel;
        public double peakDb, rmsDb; // loudest channel's peak, and average-RMS over the whole track
        public int blocks;
    }

    /** Null when the track is shorter than one block and has no samples. */
    public Result finish() {
        // a partial final block counts only if there were no full blocks (very short tracks)
        if (blockRms.isEmpty() && inBlock > 0) closeBlock();
        if (blockRms.isEmpty()) return null;
        Result res = new Result();
        res.blocks = blockRms.size();
        res.drPerChannel = new double[channels];
        double drSum = 0, pk = 0, rmsAll = 0;
        int top = Math.max(1, (int) Math.floor(blockRms.size() * 0.2));
        for (int ch = 0; ch < channels; ch++) {
            List<Double> r = new ArrayList<>(), p = new ArrayList<>();
            double meanSq = 0;
            for (double[] b : blockRms) { r.add(b[ch]); meanSq += b[ch] * b[ch]; }
            for (double[] b : blockPeak) p.add(b[ch]);
            Collections.sort(r, Collections.reverseOrder());
            Collections.sort(p, Collections.reverseOrder());
            double s = 0;
            for (int i = 0; i < top; i++) s += r.get(i) * r.get(i);
            double rmsTop = Math.sqrt(s / top);
            double peak2 = p.size() > 1 ? p.get(1) : p.get(0);
            double d = rmsTop > 0 && peak2 > 0 ? 20 * Math.log10(peak2 / rmsTop) : 0;
            res.drPerChannel[ch] = d;
            drSum += d;
            pk = Math.max(pk, trackPeak[ch]);
            rmsAll += Math.sqrt(meanSq / blockRms.size());
        }
        res.dr = drSum / channels;
        res.drRounded = (int) Math.round(res.dr);
        res.peakDb = pk > 0 ? 20 * Math.log10(pk) : -144;
        double ra = rmsAll / channels;
        res.rmsDb = ra > 0 ? 20 * Math.log10(ra) : -144;   // foobar DR Meter convention: RMS +3.01 dB (sine = peak), checked 2026-11-22
        return res;
    }

    public long frames() { return frames; }
}
