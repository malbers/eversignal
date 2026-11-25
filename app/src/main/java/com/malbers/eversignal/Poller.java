package com.malbers.eversignal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Polls the player (one request at a time) and hands the screen a fresh Snapshot.
 * getState every 500 ms; the output/queue/device calls only on a track change or every 20 s.
 */
public final class Poller implements Runnable {

    /** Everything one frame of the screen needs. Immutable once handed over. */
    public static final class Snapshot {
        public Track track;
        public long stateAt;               // when getState was read (for smooth progress)
        public String nextTitle;
        public String fileName, genre, disc, sizeBytes;
        public Bitmap cover;
        public int tint = Color.rgb(42, 38, 34);
        public Device device = new Device();
        public Session session;
        public long rttMs, lastUpdate;
        public int errors;
        public boolean online;
        // raw replies for the Music Stats / Device Stats pages (flattened, icons dropped)
        public Map<String, String> stateRaw, modelRaw, ioRaw, displayRaw, queueItem;
        public String playingFrom, audioHost;
        public Double exactBps;
        public int queuePos = -1, queueTotal = -1;
        public long queueDurationMs = -1;
        public final List<String[]> upNext = new ArrayList<>();  // {title, artist, format}
        /** The active output's own settings page: {setting, current value}; null when the player gives none. */
        public List<String[]> outputSettings;
        public String outputSettingsTitle;     // e.g. "USB DAC output"
    }

    public static final class Device {
        public String model, firmware, android, ram, flash, brightness;
        public final List<String> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        public int inputIndex = -1, outputIndex = -1;
        public String capFormat, capMax;
    }

    public interface Listener { void onSnapshot(Snapshot s); }

    /** Read by KeepOnTopService, so it needs no polling of its own while the app is alive. */
    static volatile long lastStartAt = 0;
    static volatile boolean alive = false;

    private static final long SETTLE_MS = 2000;

    private final Api api;
    private final Listener listener;
    private final Session session;
    private volatile boolean running = true;

    private final Device device = new Device();
    private String lastKey, artRef;
    private int lastState = -1;
    private final OutputHold hold = new OutputHold();
    private final VerdictSettle settle = new VerdictSettle();
    private long keyChangedAt, lastSlow, lastPoll;
    private int errors;
    private String nextTitle, fileName, genre, disc, sizeBytes, playingFrom, audioHost;
    private Double exactBps;
    private volatile List<String[]> outputSettings;
    private volatile String outputSettingsTitle;
    private Map<String, String> modelRaw, ioRaw, queueItem;
    private final Map<String, String> displayRaw = new java.util.LinkedHashMap<>();
    private int queuePos = -1, queueTotal = -1;
    private long queueDurationMs = -1;
    private List<String[]> upNext = new ArrayList<>();
    // Written by the cover thread, read by the poll loop.
    private volatile Bitmap cover;
    private volatile int tint = Color.rgb(42, 38, 34);
    private final CoverRetry coverRetry = new CoverRetry();
    private final ExecutorService coverExec = Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "eversignal-cover");
        th.setDaemon(true);
        return th;
    });

    public Poller(Api api, Session session, Listener listener) {
        this.api = api;
        this.session = session;
        this.listener = listener;
    }

    public void stop() { running = false; coverExec.shutdownNow(); }

    @Override public void run() {
        alive = true;
        readDeviceOnce();
        while (running) {
            long t0 = System.currentTimeMillis();
            Snapshot snap = new Snapshot();
            try {
                Map<String, String> s = Flat.of(new JSONObject(api.text(Api.STATE)));
                long now = System.currentTimeMillis();
                snap.rttMs = now - t0;
                Track probe = Track.from(s, false);
                boolean changed = !probe.key.equals(lastKey);
                if (changed) { keyChangedAt = now; lastKey = probe.key; }
                boolean started = changed || (probe.state == 3 && lastState != 3 && lastState != -1);
                if (started && probe.state == 3) lastStartAt = now;
                Track t = Track.from(s, now - keyChangedAt >= SETTLE_MS);
                t.applyQueueFile(queueItem, now - keyChangedAt >= SETTLE_MS);   // .m4a: the queue has the true rate
                // 1.0.12.7: .m4a codec from the file. Not on a track's first poll: playingFrom is still the last track's
                // until readQueue runs below.
                M4aCodec.Info ci = changed ? null : codecOf(t);
                if (ci != null) t.applyM4aCodec(ci.codec, ci.channels, ci.atmos, now - keyChangedAt >= SETTLE_MS);
                if (ci != null) t.applyHeaderRate(ci.rate, now - keyChangedAt >= SETTLE_MS);   // 1.0.14: ALAC "0.001 kHz"
                hold.apply(t, t.state == 3 && now - lastStartAt >= SETTLE_MS && now - keyChangedAt >= SETTLE_MS, now);
                if (recheck) { recheck = false; settle.reset(); }   // 1.0.12-2: back from another screen
                settle.apply(t, now);   // after the hold: only decides when the badge may change

                if (changed || now - lastSlow > 20_000) {
                    lastSlow = now;
                    readIo();
                    readQueue(s, t);
                }
                if (coverAgain) { coverAgain = false; artRef = "again"; }   // 1.0.12: file access just granted
                String ref = t.artUrl != null ? "u:" + t.artUrl : t.artFile != null ? "f:" + t.artFile : t.artPath != null ? "p:" + t.artPath : t.artId != null ? "i:" + t.artId : null;
                if (ref == null ? artRef != null : !ref.equals(artRef)) { artRef = ref; cover = null; coverRetry.start(now); }   // never show the last track's cover
                int coverGen = coverRetry.take(now);
                if (coverGen > 0) {
                    final Track ct = t;
                    final String local = filesOn && ArtLoader.isLocal(playingFrom) ? playingFrom : null;   // 1.0.12-5: File access switch
                    try { coverExec.execute(() -> loadCover(ct, local, coverGen)); }
                    catch (Exception e) { coverRetry.finish(coverGen, CoverRetry.Outcome.FAIL, now); }
                }

                session.record(t, lastPoll == 0 ? 0 : now - lastPoll);
                lastPoll = now;
                lastState = t.state;

                snap.track = t;
                snap.stateRaw = Raw.clean(s);
                snap.stateAt = now;
                snap.online = true;
                snap.lastUpdate = now;
            } catch (Exception e) {
                errors++;
                snap.online = false;
            }
            snap.nextTitle = nextTitle;
            snap.fileName = fileName; snap.genre = genre; snap.disc = disc; snap.sizeBytes = sizeBytes;
            snap.cover = cover;
            snap.tint = tint;
            snap.device = device;
            snap.session = session;
            snap.errors = errors;
            snap.modelRaw = modelRaw;
            snap.ioRaw = ioRaw;
            snap.displayRaw = displayRaw;
            snap.queueItem = queueItem;
            snap.playingFrom = playingFrom;
            snap.audioHost = audioHost;
            snap.exactBps = exactBps;
            snap.outputSettings = outputSettings;
            snap.outputSettingsTitle = outputSettingsTitle;
            snap.queuePos = queuePos; snap.queueTotal = queueTotal; snap.queueDurationMs = queueDurationMs;
            snap.upNext.addAll(upNext);
            listener.onSnapshot(snap);
            long wait = (snap.online ? 500 : 2500) - (System.currentTimeMillis() - t0);
            if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException e) { break; }
        }
        session.flush();
        alive = false;
    }

    private void readDeviceOnce() {
        String[][] display = {{Api.KNOB, "knob"}, {Api.VU_STYLES, "vu"}, {Api.SPECTRUM_STYLES, "spectrum"}};
        for (String[] d : display) {
            try {
                Map<String, String> m = Flat.of(new JSONObject(api.text(d[0])));
                if ("knob".equals(d[1])) {
                    displayRaw.put("Knob brightness", Flat.str(m, "currentValue") + " / " + Flat.str(m, "maxValue"));
                } else {
                    String idx = Flat.str(m, "currentIndex");
                    String name = idx == null ? null : Flat.str(m, "data[" + idx + "].title");
                    StringBuilder all = new StringBuilder();
                    for (int i = 0; i < 12 && Flat.str(m, "data[" + i + "].title") != null; i++) {
                        if (all.length() > 0) all.append(", ");
                        all.append(Flat.str(m, "data[" + i + "].title"));
                    }
                    displayRaw.put("vu".equals(d[1]) ? "VU style" : "Spectrum style", name == null ? idx : name);
                    displayRaw.put("vu".equals(d[1]) ? "VU styles" : "Spectrum styles", all.toString());
                }
            } catch (Exception ignored) { }
        }
        try {
            Map<String, String> m = Flat.of(new JSONObject(api.text(Api.MODEL)));
            modelRaw = Raw.clean(m);
            device.model = Flat.str(m, "model");
            device.firmware = Flat.str(m, "firmware");
            String a = Flat.str(m, "androidversion");
            device.android = a == null ? null : "Android " + a;
            device.ram = Flat.str(m, "ram");
            device.flash = Flat.str(m, "flash");
        } catch (Exception ignored) { }
        try {
            Map<String, String> b = Flat.of(new JSONObject(api.text(Api.BRIGHTNESS)));
            Double cur = Flat.num(Flat.str(b, "currentValue")), max = Flat.num(Flat.str(b, "maxValue"));
            if (cur != null && max != null && max > 0) {
                device.brightness = Math.round(cur * 100 / max) + "%";
                displayRaw.put("Screen brightness", Math.round(cur) + " / " + Math.round(max) + " (" + device.brightness + ")");
            }
        } catch (Exception ignored) { }
    }

    private void readIo() {
        try {
            JSONObject j = new JSONObject(api.text(Api.IO));
            ioRaw = Raw.clean(Flat.of(j));
            List<String> in = new ArrayList<>(), out = new ArrayList<>();
            JSONArray ia = j.optJSONArray("inputData"), oa = j.optJSONArray("outputData");
            if (ia != null) for (int i = 0; i < ia.length(); i++) in.add(ia.optJSONObject(i).optString("name"));
            if (oa != null) for (int i = 0; i < oa.length(); i++) out.add(oa.optJSONObject(i).optString("name"));
            synchronized (device) {
                device.inputs.clear(); device.inputs.addAll(in);
                device.outputs.clear(); device.outputs.addAll(out);
                device.inputIndex = j.optInt("inputIndex", -1);
                device.outputIndex = j.optInt("outputIndex", -1);
                JSONObject info = j.optJSONObject("outputInfo");
                readOutputSettings(info);
                device.capFormat = null; device.capMax = null;
                if (info != null) {
                    String f = info.optString("format", "");
                    device.capFormat = f.isEmpty() ? null : f.replace(",", " · ");
                    String sr = info.optString("sampleRate", "");
                    Double hz = Flat.num(sr);
                    device.capMax = hz == null ? null : "up to " + Track.rateText(hz, false);
                }
            }
        } catch (Exception ignored) { }
    }

    /** The active output's settings page . One small read, get-only. */
    private void readOutputSettings(JSONObject info) {
        String path = info == null ? null : Api.outputOptions(blank(info.optString("setUrl")));
        if (path == null) { outputSettings = null; outputSettingsTitle = null; return; }
        try {
            outputSettings = outputSettings(new JSONObject(api.text(path)));
            outputSettingsTitle = blank(info.optString("title"));
        } catch (Exception e) {
            outputSettings = null;
            outputSettingsTitle = null;
        }
    }

    /**
     * {setting, current value} for each item. The value's shape depends on the control (measured on USB DAC and
     * XLR/RCA, 2026-11-15): a list choice in selectTitle, a slider in currentTitle, a PCM + DSD pair in
     * pcmObj/dsdObj.currentTitle. Unknown shapes give null (shown as N/A), never a guess.
     */
    static List<String[]> outputSettings(JSONObject reply) {
        List<String[]> out = new ArrayList<>();
        JSONArray items = reply.optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || blank(it.optString("title")) == null) continue;
            String v = blank(it.isNull("selectTitle") ? null : it.optString("selectTitle"));
            if (v == null) v = blank(it.isNull("currentTitle") ? null : it.optString("currentTitle"));
            if (v == null) {
                StringBuilder sb = new StringBuilder();
                for (java.util.Iterator<String> k = it.keys(); k.hasNext(); ) {
                    String key = k.next();
                    JSONObject o = key.endsWith("Obj") ? it.optJSONObject(key) : null;
                    String ct = o == null || o.isNull("currentTitle") ? null : blank(o.optString("currentTitle"));
                    if (ct != null) sb.append(sb.length() == 0 ? "" : " · ").append(ct);
                }
                v = sb.length() == 0 ? null : sb.toString();
            }
            out.add(new String[]{it.optString("title").trim(), v});
        }
        return out;
    }

    /** "Up next" plus file details for the current local track. Small, bounded reads only. */
    private void readQueue(Map<String, String> s, Track t) {
        nextTitle = null; fileName = null; genre = null; disc = null; sizeBytes = null;
        queueItem = null; queuePos = -1; queueTotal = -1; queueDurationMs = -1; queueCodec = null;
        upNext = new ArrayList<>();
        audioHost = null; exactBps = null;
        // Where it plays from: the stream link WITHOUT its signed token or the file path.
        // a stream URL only counts while the player says it is streaming (playingMusic can carry a stale one)
        playingFrom = t.ownPlayer && t.service != null ? hideToken(Flat.str(s, "playingMusic.streamUrl")) : null;
        // "sent from another device" only for real casts; playType 8 is the A6's own Apple/Amazon Music app
        if (!t.ownPlayer) playingFrom = t.playerLabel == null ? null
            : t.playerLabel + (CAST.matcher(t.playerLabel).find() ? " (sent from another device)" : "");
        if (!t.hasQueue) return;
        try {
            Double ti = Flat.num(Flat.str(s, "trackIndex"));
            int idx = ti == null ? -1 : ti.intValue();
            // trackIndex is -1 for TIDAL, and TIDAL marks EVERY queue item "active" (seen 2026-11-15), so the
            // current track is found by title + artist, searching near where it was last found first.
            int[] windows = idx >= 0 ? new int[]{idx} : new int[]{Math.max(0, lastQueueIdx - 3), 0, 30, 60};
            // 1.0.14: an exact id match anywhere wins over a title match. Three copies of one song with the same
            // title (BINI: ALAC, AAC, Atmos) made the ALAC play show the Atmos copy's details (seen 2026-11-24).
            JSONArray fbArr = null; int fbI = -1, fbStart = 0;
            for (int start : windows) {
                JSONObject q = new JSONObject(api.text(Api.queue(start, idx >= 0 ? 6 : 30)));
                queueTotal = q.optInt("total", -1);
                long td = q.optLong("totalDuration", -1);
                queueDurationMs = td > 0 ? td : -1;
                JSONArray arr = q.optJSONArray("array");
                if (arr == null) break;
                if (idx >= 0) { if (arr.length() > 0) fill(arr, 0, start, t); return; }
                int i = pick(arr, t.trackId, t.title, t.artist, true);
                if (i >= 0) { fill(arr, i, start, t); return; }
                if (fbArr == null && (i = pick(arr, t.trackId, t.title, t.artist, false)) >= 0) {
                    fbArr = arr; fbI = i; fbStart = start;
                }
                if (queueTotal >= 0 && start + 30 >= queueTotal && start != Math.max(0, lastQueueIdx - 3)) break;
            }
            if (fbArr != null) fill(fbArr, fbI, fbStart, t);
        } catch (Exception ignored) { }
    }

    private int lastQueueIdx = 0;

    /**
     * Index of the current track in one queue window, or -1. byId: only an exact id match; else only a title (+ artist)
     * match. 1.0.8: the state and the queue can title the same file differently (file name vs tag, seen 2026-11-17:
     * "01 ALAC 24-96" vs "EverSignal test ALAC 24-96", same id 32592), so the id is tried first. The title still
     * counts as a fallback: for TIDAL the state's id differs from the queue's (seen 2026-11-15).
     */
    static int pick(JSONArray arr, String trackId, String title, String artist, boolean byId) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            if (byId) {
                String id = it.optString("id", "");
                if (trackId != null && !id.isEmpty() && !"-1".equals(id) && trackId.equals(id)) return i;
            } else if (title != null && title.equalsIgnoreCase(it.optString("title"))) {
                String a = it.optString("artist", "");
                if (artist == null || a.isEmpty() || artist.equalsIgnoreCase(a)) return i;
            }
        }
        return -1;
    }

    /** Fills the current-item fields from item i of one queue window. */
    private void fill(JSONArray arr, int i, int start, Track t) {
        {
            {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) return;
                queuePos = start + i + 1;
                lastQueueIdx = start + i;
                queueItem = Raw.cleanQueueItem(Flat.of(it));
                fileName = blank(it.optString("fileName"));
                genre = blank(it.optString("genreName"));
                int dn = it.optInt("diskNumber", 0);
                disc = dn > 0 ? String.valueOf(dn) : null;
                long fs = it.optLong("fileSize", 0);
                sizeBytes = fs > 0 ? String.format(java.util.Locale.US, "%.1f MB", fs / 1048576.0) : null;
                String path = blank(it.optString("path"));
                String url = blank(it.optString("url"));
                if (path != null && !path.startsWith("http")) playingFrom = path;
                else if (url != null && !url.startsWith("http") && playingFrom == null) playingFrom = url;
                // 1.0.14 (Pierre C., measured on Michael's A6 2026-11-23): a LIBRARY play has no path or url at all,
                // only "uri", relative to its drive ("/EverSoloMusic/..."). Without it EverSignal took the track for a
                // stream: no DR, no folder art, no lyrics, no .m4a codec.
                String uri = blank(it.optString("uri"));
                if (playingFrom == null && uri != null && filesOn) playingFrom = resolveUri(uri);
                // the queue also names the codec ("EAC3", "AAC", "ALAC"): used when the file header can't be read
                queueCodec = blank(it.optString("codec"));
                if (url != null && url.startsWith("http") && t.service != null) {
                    try { audioHost = new java.net.URL(url).getHost(); } catch (Exception ignored) { }
                    if (playingFrom == null) playingFrom = hideToken(url);
                }
                long br = it.optLong("bitrate", 0);
                exactBps = br > 0 ? (double) br : null;
                for (int k = i + 1; k < arr.length() && upNext.size() < 5; k++) {
                    JSONObject nx = arr.optJSONObject(k);
                    if (nx == null) continue;
                    String ext = nx.optString("displayExtension", nx.optString("extension", ""));
                    double sr = nx.optDouble("sampleRateNumber", nx.optDouble("SampleRate", 0));
                    int bits = nx.optInt("bits", 0);
                    String fmt = ext.toUpperCase(java.util.Locale.US)
                        + (sr > 0 ? " · " + Track.rateText(sr, false) : "") + (bits > 1 ? " · " + bits + "-bit" : "");
                    upNext.add(new String[]{nx.optString("title"), nx.optString("artist"), fmt});
                }
                if (!upNext.isEmpty()) nextTitle = blank(upNext.get(0)[0]);
            }
        }
    }

    /** 1.0.14: decode a local picture file at about maxPx, or null (missing, unreadable, not a picture). */
    static Bitmap decodeFile(String path, int maxPx) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.canRead() || f.length() <= 64) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static String blank(String v) { return v == null || v.trim().isEmpty() ? null : v.trim(); }

    private static final java.util.regex.Pattern CAST =
        java.util.regex.Pattern.compile("(?i)airplay|connect|dlna|upnp|roon|bluetooth|squeeze|naa|hiplay|qplay|cast");

    /** Host + path of a stream link; a signed query (time-limited token) is never shown. */
    static String hideToken(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + " (signed link hidden)";
    }

    private volatile boolean coverAgain;
    /** 1.0.12-5: Settings › File access switched Off = no folder reads, the player's own cover only. */
    private volatile boolean filesOn = true;

    /** 1.0.12.7: the playing .m4a's codec, read once per file from its header (File access on only). */
    private String codecPath;
    private M4aCodec.Info codecValue;
    private M4aCodec.Info codecOf(Track t) {
        if (!t.m4a) return null;
        if (filesOn && ArtLoader.isLocal(playingFrom) && M4aCodec.isM4a(playingFrom)) {
            if (!playingFrom.equals(codecPath)) { codecPath = playingFrom; codecValue = M4aCodec.read(new java.io.File(playingFrom)); }
            if (codecValue != null) return codecValue;
        }
        return fromQueue(queueCodec);   // 1.0.14: no file (or File access off): the queue's own codec name
    }

    private String queueCodec;

    /** 1.0.14: the queue's codec name in M4aCodec's terms. Channels unknown here, so Dolby shows no channel count. */
    static M4aCodec.Info fromQueue(String c) {
        if (c == null) return null;
        switch (c.trim().toUpperCase(java.util.Locale.US)) {
            case "EAC3": case "E-AC-3": case "EC3": return new M4aCodec.Info("ec-3", null, false);
            case "AC3": case "AC-3": return new M4aCodec.Info("ac-3", null, false);
            case "AC4": case "AC-4": return new M4aCodec.Info("ac-4", null, false);
            case "AAC": return new M4aCodec.Info("mp4a", null, false);
            case "ALAC": return new M4aCodec.Info("alac", null, false);
            case "FLAC": return new M4aCodec.Info("flac", null, false);
            default: return null;
        }
    }

    /** 1.0.14: drive root for a queue "uri", found once by checking which drive has the file. */
    private static final java.util.Map<String, String> uriRoots = new java.util.HashMap<>();
    static String resolveUri(String uri) {
        if (uri == null || !uri.startsWith("/")) return null;
        if (uri.startsWith("/storage/") || uri.startsWith("/sdcard/")) return uri;   // already absolute
        String top = uri.indexOf('/', 1) > 0 ? uri.substring(0, uri.indexOf('/', 1)) : uri;
        String known = uriRoots.get(top);
        if (known != null && new java.io.File(known + uri).exists()) return known + uri;
        java.util.List<String> roots = new java.util.ArrayList<>();
        java.io.File[] vols = new java.io.File("/storage").listFiles();
        if (vols != null) for (java.io.File v : vols) if (!"self".equals(v.getName()) && !"emulated".equals(v.getName())) roots.add(v.getPath());
        roots.add("/storage/emulated/0");
        for (String r : roots) if (new java.io.File(r + uri).exists()) { uriRoots.put(top, r); return r + uri; }
        return null;
    }
    void setFilesOn(boolean on) { filesOn = on; }
    /** 1.0.12: load the current track's cover again (called after file access is granted). */
    void retryCover() { coverAgain = true; }

    private volatile boolean recheck;
    /** EverSignal is back in front: settings may have changed meanwhile (EQ, output), so show Checking first. */
    void recheckVerdict() { recheck = true; }

    /** Runs on the cover thread. A FAIL is retried by CoverRetry; a result for an older track is dropped. */
    private void loadCover(Track t, String localPath, int gen) {
        Bitmap b = null;
        CoverRetry.Outcome out = CoverRetry.Outcome.NONE;
        // 1.0.12: a file on the player's own storage: the album folder FIRST (folder, disc-set parent, embedded art).
        // Measured on the A6 2026-11-20 (T16 Disc 1): when the player has no art it does NOT always say so with 806;
        // getImage can return a generic 1080x1080 purple music-note picture, which looked like a cover and kept the
        // fallback from ever running. Asking the folder first avoids that, and needs no network.
        if (localPath != null) {
            b = ArtLoader.mainCover(localPath, 700);
            if (b != null) out = CoverRetry.Outcome.OK;
        }
        // 1.0.14: a cover the player cached as a file (Apple Music app). Read straight from storage; needs File access.
        if (b == null && t.artFile != null && filesOn) {
            b = decodeFile(t.artFile, 700);
            if (b != null && isPlaceholder(b)) b = null;
            out = b != null ? CoverRetry.Outcome.OK : CoverRetry.Outcome.NONE;
        }
        if (b == null) try {
            byte[] data = t.artUrl != null ? Api.bytes(t.artUrl, 8000)
                : t.artPath != null ? Api.bytes(api.url(t.artPath), 8000)
                : t.artId != null ? Api.bytes(api.url(Api.image(t.artId)), 8000) : null;
            // The player answers {"status":806} (JSON, not an image) when it has no art: final, no retry.
            if (data == null || data.length <= 64 || data[0] == '{') {
                out = CoverRetry.Outcome.NONE;
            } else {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 700) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                out = b != null ? CoverRetry.Outcome.OK : CoverRetry.Outcome.FAIL;   // cut-off bytes: try again
                // 1.0.12-2: the player's generic music-note picture is "no art", not a cover (Michael 2026-11-20:
                // "No cover art" first, then the orange note popped in on the retry). Checked at full size.
                if (b != null && o.inSampleSize == 1 && isPlaceholder(b)) { b = null; out = CoverRetry.Outcome.NONE; }
            }
        } catch (Exception e) {
            out = CoverRetry.Outcome.FAIL;   // timeout, network error
        }
        // Folder empty (or unreadable) → the player's picture, as before. Václav H.'s "=" folders and Pierre C.'s
        // cover.jpg-only albums are both covered by the folder-first step above.
        if (!coverRetry.finish(gen, out, System.currentTimeMillis())) return;
        int tn = b == null ? Color.rgb(42, 38, 34) : tintOf(b);
        cover = b;
        tint = tn;
    }

    static boolean isPlaceholder(Bitmap b) {
        return PlayerPlaceholder.is(new PlayerPlaceholder.Pixels() {
            public int width() { return b.getWidth(); }
            public int height() { return b.getHeight(); }
            public int get(int x, int y) { return b.getPixel(x, y); }
        });
    }

    /** A dark, low-saturation tint from the cover's average colour (design: OKLCH L about .34, low chroma). */
    static int tintOf(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 16, 16, true);
        long r = 0, g = 0, bl = 0;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int c = s.getPixel(x, y);
            r += Color.red(c); g += Color.green(c); bl += Color.blue(c);
        }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / 256), (int) (g / 256), (int) (bl / 256), hsv);
        hsv[1] = Math.min(0.55f, Math.max(0.25f, hsv[1]));
        hsv[2] = 0.32f;
        return Color.HSVToColor(hsv);
    }
}
