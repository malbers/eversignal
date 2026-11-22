package com.malbers.eversignal;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1.0.12.12: Dynamic Range, on demand (Pierre C.). Michael 2026-11-22: two buttons, "Measure track" and
 * "Measure album", in Detail; the track's DR also in the Simple view once measured. Decoding is the
 * EversoloStats 0.3.1 probe, proven on the A6 Gen 1 the same day: matches foobar's DR Meter 1.0.8 to 0.01 dB,
 * ~25x real time for 24/192 FLAC while music plays, no audible effect on playback.
 *
 * Results are cached per file (path + size + modified time): measured once, shown instantly after that.
 */
final class DrScan {
    private DrScan() {}

    static final String[] AUDIO = {".flac", ".wav", ".m4a", ".alac", ".mp3", ".aif", ".aiff", ".aac", ".ogg", ".opus"};

    static final class Item {
        final String name; final double dr, peakDb, rmsDb;
        Item(String name, double dr, double peakDb, double rmsDb) { this.name = name; this.dr = dr; this.peakDb = peakDb; this.rmsDb = rmsDb; }
        int rounded() { return (int) Math.round(dr); }
    }

    /** The album folder as the view shows it. */
    static final class Album {
        final String folder;
        int total, done, failed;
        boolean running;
        final List<Item> items = new ArrayList<>();
        Album(String folder) { this.folder = folder; }
        /** Album DR = the rounded average of the tracks' rounded DRs, as foobar's album "Official DR value". Null until all are in. */
        Integer value() {
            if (items.isEmpty() || items.size() + failed < total) return null;
            double s = 0;
            for (Item i : items) s += i.rounded();
            return (int) Math.round(s / items.size());
        }
    }

    /**
     * 1.0.12.13: names for the per-track list without what they all share (Michael: "Mostly Piano - 10 - Ashokan
     * Farewell - " on every line cut off the part that differs) and without the extension. The shared part is cut
     * back to a separator, so "01 - A" / "02 - B" keep their numbers.
     */
    static List<String> shortNames(List<String> names) {
        List<String> base = new ArrayList<>();
        for (String n : names) { int dot = n.lastIndexOf('.'); base.add(dot > 0 ? n.substring(0, dot) : n); }
        if (base.size() < 2) return base;
        String p = base.get(0);
        for (String n : base) { int k = 0; while (k < p.length() && k < n.length() && p.charAt(k) == n.charAt(k)) k++; p = p.substring(0, k); }
        int cut = p.length();
        while (cut > 0 && " -_.".indexOf(p.charAt(cut - 1)) < 0) cut--;   // back to a separator
        List<String> out = new ArrayList<>();
        for (String n : base) { String r = n.substring(cut).trim(); out.add(r.isEmpty() ? n : r); }
        return out;
    }

    static boolean isAudio(String n) {
        if (n == null) return false;
        String l = n.toLowerCase(Locale.US);
        for (String a : AUDIO) if (l.endsWith(a)) return true;
        return false;
    }

    static File[] tracks(File dir) {
        File[] f = dir == null ? null : dir.listFiles(x -> x.isFile() && isAudio(x.getName()) && !x.getName().startsWith("."));
        if (f == null) return new File[0];
        Arrays.sort(f, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return f;
    }

    // ---------------------------------------------------------------- cache (one writer: the DR executor)

    private static Map<String, double[]> cache;
    private static File cacheFile;

    static synchronized void init(File filesDir) {
        if (cache != null) return;
        cacheFile = new File(filesDir, "dr-cache.json");
        cache = new HashMap<>();
        try {
            if (cacheFile.isFile()) {
                JSONObject j = new JSONObject(new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8));
                for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONArray a = j.getJSONArray(k);
                    cache.put(k, new double[]{a.getDouble(0), a.getDouble(1), a.getDouble(2)});
                }
            }
        } catch (Exception ignored) { cache.clear(); }
    }

    private static String key(File f) { return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified(); }

    static synchronized Item cached(File f) {
        double[] v = cache == null || f == null ? null : cache.get(key(f));
        return v == null ? null : new Item(f.getName(), v[0], v[1], v[2]);
    }

    private static synchronized void put(File f, Item i) {
        if (cache == null) return;
        cache.put(key(f), new double[]{i.dr, i.peakDb, i.rmsDb});
        try {
            JSONObject j = new JSONObject();
            for (Map.Entry<String, double[]> e : cache.entrySet()) {
                JSONArray a = new JSONArray();
                for (double d : e.getValue()) a.put(d);
                j.put(e.getKey(), a);
            }
            File tmp = new File(cacheFile.getPath() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { w.write(j.toString()); }
            if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile); }   // write-then-rename: never a half file
        } catch (Exception ignored) { }
    }

    /** The album as far as the cache knows (no decoding): cheap, run on every album change. */
    static Album fromCache(File dir) {
        Album a = new Album(dir.getPath());
        File[] fs = tracks(dir);
        a.total = fs.length;
        for (File f : fs) { Item i = cached(f); if (i != null) { a.items.add(i); a.done++; } }
        return a;
    }

    interface Progress { void update(Album a); }
    interface Cancelled { boolean get(); }

    /** One track: cached, or decoded and cached. Null if the file can't be read, or on cancel. */
    static Item measureTrack(File f, Cancelled cancel) { return measureTrack(f, cancel, false); }

    /** fresh = ignore the saved result and decode again (1.0.12.13, "Measure again"). */
    static Item measureTrack(File f, Cancelled cancel, boolean fresh) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        Item i = fresh ? null : cached(f);
        if (i != null) return i;
        i = decode(f, cancel);
        if (i != null && !cancel.get()) put(f, i);
        return cancel.get() ? null : i;
    }

    /** Every track in the folder not already cached. Reports after each track. */
    static Album measureAlbum(File dir, Progress p, Cancelled cancel) { return measureAlbum(dir, p, cancel, false); }

    static Album measureAlbum(File dir, Progress p, Cancelled cancel, boolean fresh) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        Album a = new Album(dir.getPath());
        File[] fs = tracks(dir);
        a.total = fs.length;
        a.running = true;
        p.update(a);
        for (File f : fs) {
            if (cancel.get()) break;
            Item i = measureTrack(f, cancel, fresh);
            if (cancel.get()) break;
            if (i != null) a.items.add(i); else a.failed++;
            a.done++;
            p.update(a);
        }
        a.running = false;
        p.update(a);
        return a;
    }

    static Item decode(File f, Cancelled cancel) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(f.getPath());
            int ti = -1; MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat m = ex.getTrackFormat(i);
                String mime = m.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { ti = i; fmt = m; break; }
            }
            if (ti < 0) return null;
            ex.selectTrack(ti);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            DrMeter dr = new DrMeter(ch, sr);
            int enc = fmt.containsKey("pcm-encoding") ? fmt.getInteger("pcm-encoding") : 2;
            float[] buf = new float[0];
            if ("audio/raw".equals(mime)) {
                // the A6's extractor already hands out PCM for WAV and FLAC: read it straight
                ByteBuffer raw = ByteBuffer.allocateDirect(1 << 20);
                while (!cancel.get()) {
                    raw.clear();
                    int n = ex.readSampleData(raw, 0);
                    if (n < 0) break;
                    raw.position(0); raw.limit(n);
                    buf = toFloat(raw.slice().order(ByteOrder.nativeOrder()), enc, buf);
                    dr.feed(buf, samples(n, enc));
                    ex.advance();
                }
            } else {
                fmt.setInteger("pcm-encoding", 4);   // ask for float, full precision
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(fmt, null, null, 0);
                codec.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inEos = false, outEos = false;
                enc = 2;
                while (!outEos && !cancel.get()) {
                    if (!inEos) {
                        int ib = codec.dequeueInputBuffer(10_000);
                        if (ib >= 0) {
                            ByteBuffer in = codec.getInputBuffer(ib);
                            int n = ex.readSampleData(in, 0);
                            if (n < 0) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                            else { codec.queueInputBuffer(ib, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                        }
                    }
                    int ob = codec.dequeueOutputBuffer(info, 10_000);
                    if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        if (of.containsKey("pcm-encoding")) enc = of.getInteger("pcm-encoding");
                    } else if (ob >= 0) {
                        ByteBuffer o = codec.getOutputBuffer(ob);
                        if (o != null && info.size > 0) {
                            o.position(info.offset); o.limit(info.offset + info.size);
                            buf = toFloat(o.slice().order(ByteOrder.nativeOrder()), enc, buf);
                            dr.feed(buf, samples(info.size, enc));
                        }
                        codec.releaseOutputBuffer(ob, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    }
                }
            }
            if (cancel.get()) return null;
            DrMeter.Result r = dr.finish();
            return r == null ? null : new Item(f.getName(), r.dr, r.peakDb, r.rmsDb);
        } catch (Exception e) {
            return null;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) { }
            ex.release();
        }
    }

    private static int samples(int bytes, int enc) {
        switch (enc) {
            case 4: case 22: return bytes / 4;
            case 21: return bytes / 3;
            case 3: return bytes;
            default: return bytes / 2;
        }
    }

    // one decode at a time (the DR executor is single-threaded), so shared scratch arrays are safe
    private static short[] sh = new short[0];
    private static int[] in32 = new int[0];

    private static float[] toFloat(ByteBuffer b, int enc, float[] reuse) {
        int n = samples(b.remaining(), enc);
        float[] f = reuse.length >= n ? reuse : new float[n];
        switch (enc) {
            case 4: b.asFloatBuffer().get(f, 0, n); break;
            case 22: {
                if (in32.length < n) in32 = new int[n];
                b.asIntBuffer().get(in32, 0, n);
                for (int i = 0; i < n; i++) f[i] = in32[i] / 2147483648f;
                break;
            }
            case 21: for (int i = 0; i < n; i++) {
                int lo = b.get() & 0xff, mid = b.get() & 0xff, hi = b.get();
                f[i] = ((hi << 16) | (mid << 8) | lo) / 8388608f;
            } break;
            case 3: for (int i = 0; i < n; i++) f[i] = ((b.get() & 0xff) - 128) / 128f; break;
            default: {
                if (sh.length < n) sh = new short[n];
                b.asShortBuffer().get(sh, 0, n);
                for (int i = 0; i < n; i++) f[i] = sh[i] / 32768f;
            }
        }
        return f;
    }
}
