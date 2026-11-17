package com.malbers.eversignal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the screen shows about the current track, read from getState.
 *
 * Rules measured on the DMP-A6 (2026-11-15):
 * - playType 5 = the A6's own player: playingMusic is live. Anything else (AirPlay, TIDAL Connect)
 *   leaves playingMusic STALE and puts the track in everSoloPlayInfo.everSoloPlayAudioInfo.
 * - Rates and bits read 0 for the first ~3 s of a stream.
 * - The output block lags a track change by 1-2 polls (the caller passes outputSettled=false then).
 * - Output bit depth is always 0 (never reported).
 */
public final class Track {

    public enum Badge { DSD_NATIVE, DSD_TO_PCM, MQA, LOSSY, BIT_PERFECT, VOLUME, HIRES_STREAM, RESAMPLED, EQ_ACTIVE, PAUSED, NONE }

    /** Volume can't alter the samples: locked (fixed output), at 0 dB, or at its maximum step. */
    public boolean volumeOk = true;
    /** Bitrate as a number (bits per second), for the session's lowest/highest. */
    public Double bitrateBps;
    /** "24-bit / 48 kHz", or "DSD256 / 11.29 MHz": shown at the end of the badge sentence. */
    public String formatShort;

    // identity
    public String title, artist, album, key;
    public final List<String> tags = new ArrayList<>();
    public String trackId, albumId, fileSize, replayGain;
    public boolean favourite, lyrics;
    // playback
    public int state;          // 3 playing, 4 paused, 0 stopped
    public long positionMs, durationMs;
    // source
    public boolean ownPlayer;
    public String playerLabel, service, quality;
    public boolean canSeek, hasQueue;
    // signal
    public String container, codec, bitrate;
    public Double rateHz, bits, channels;
    public boolean dsd, lossy;
    public String outCodec;
    public Double outRateHz;
    public String iface, ifaceRaw;
    public boolean mqaOut, eq, dsp, sub, mute, volumeLocked, isDsdOut;
    public String volume, sacdArea;
    // art: a web URL, a path on the player, or a library track id
    public String artUrl, artPath, artId;

    public Badge badge = Badge.NONE;
    public String badgeText = "", badgeNote = "";

    private static final Pattern LOSSY = Pattern.compile("(?i)^(mp3|aac|m4a|ogg|opus|wma|vorbis|sbc|ldac|aptx)$");

    public static Track from(Map<String, String> s, boolean outputSettled) {
        Track t = new Track();
        String pm = "playingMusic.", info = "everSoloPlayInfo.", ain = info + "everSoloPlayAudioInfo.",
            aout = info + "everSoloPlayOutputInfo.", vol = "volumeData.";

        Double pt = Flat.num(Flat.str(s, "playType"));
        t.ownPlayer = pt == null || pt.intValue() == 5;
        Double st = Flat.num(Flat.str(s, "state"));
        t.state = st == null ? 0 : st.intValue();
        Double pos = Flat.num(Flat.str(s, "position"));
        if (pos == null) pos = Flat.num(Flat.str(s, info + "currentPosition"));
        Double dur = Flat.num(Flat.str(s, "duration"));
        if (dur == null || dur <= 0) dur = Flat.num(Flat.str(s, info + "duration"));
        t.positionMs = pos == null ? 0 : pos.longValue();
        t.durationMs = dur == null ? 0 : dur.longValue();

        String label = Flat.str(s, info + "playTypeSubtitle");
        t.playerLabel = t.ownPlayer ? "Internal player" : pretty(label);

        if (t.ownPlayer) {
            t.title = Flat.str(s, pm + "title");
            t.artist = Flat.str(s, pm + "artist");
            String rawAlbum = Flat.str(s, pm + "album");
            splitAlbum(t, rawAlbum);
            t.container = upper(Flat.str(s, pm + "extension"));
            t.codec = Flat.str(s, pm + "codec");
            t.rateHz = Flat.pos(s, pm + "sampleRateNumber");
            if (t.rateHz == null) t.rateHz = parseRate(Flat.str(s, pm + "sampleRate"));
            t.bits = Flat.pos(s, pm + "bits");
            t.channels = Flat.pos(s, pm + "channels");
            String br = Flat.str(s, pm + "bitrate");
            t.bitrate = Flat.pos(s, pm + "bitrate") == null ? null : br.replace(',', '.');
            t.trackId = Flat.str(s, pm + "id");
            t.albumId = Flat.str(s, pm + "albumId");
            t.fileSize = Flat.str(s, pm + "fileSize");
            t.replayGain = Flat.str(s, pm + "replayGain");
            t.favourite = Flat.yes(s, pm + "favor");
            t.lyrics = Flat.yes(s, "hasLyric");
            t.service = Flat.str(s, pm + "streamId");
            t.quality = Flat.str(s, pm + "streamQuality");
            t.artUrl = firstUrl(Flat.str(s, pm + "albumArtBig"), Flat.str(s, pm + "albumArt"));
            String type = Flat.str(s, pm + "type");
            // Own files: type 1 = played from the Library, type 2 = played from the file browser (measured
            // 2026-11-17; covers were missing for file-browser play, reported by an owner). getImage serves both.
            if (t.artUrl == null && t.trackId != null && ("1".equals(type) || "2".equals(type))) t.artId = t.trackId;
            if (t.service != null) t.playerLabel = "Internal player";
        } else {
            t.title = Flat.str(s, ain + "songName");
            t.artist = Flat.str(s, ain + "artistName");
            splitAlbum(t, Flat.str(s, ain + "albumName"));
            t.container = upper(Flat.str(s, ain + "audioDecodec"));
            t.rateHz = Flat.pos(s, ain + "audioSampleRate");
            t.bits = Flat.pos(s, ain + "audioBitsPerSample");
            t.channels = Flat.pos(s, ain + "audioChannels");
            Double kbps = Flat.pos(s, ain + "audioBitrate");
            t.bitrate = kbps == null ? null : (kbps > 20000 ? fmt1(kbps / 1e6) + " Mbps" : fmt1(kbps) + " kbps");
            t.service = label;
            String art = Flat.str(s, ain + "albumUrl");
            String icon = Flat.str(s, info + "icon");
            t.artUrl = firstUrl(art, icon);
            if (t.artUrl == null) t.artPath = icon != null && icon.startsWith("/SystemSettings/") ? icon : null;
        }
        t.canSeek = Flat.yes(s, info + "isCanSeek") || t.ownPlayer;
        t.hasQueue = t.ownPlayer && Flat.yes(s, "hasPlayQueue");
        t.key = (t.ownPlayer ? "own" : "ext") + "|" + Flat.str(s, pm + "id") + "|" + t.title + "|" + t.artist;

        String c = (t.container == null ? "" : t.container) + " " + (t.codec == null ? "" : t.codec);
        t.dsd = c.matches("(?is).*\\b(dsf|dff|dsd|iso)\\b.*") || c.toUpperCase(Locale.US).contains("DSD");
        t.lossy = t.container != null && LOSSY.matcher(t.container).matches();
        if (t.dsd && t.bits == null) t.bits = 1.0;
        if (t.lossy) t.bits = null; // MP3 reports 0; never show "0 bit"

        // output
        if (outputSettled) {
            t.outCodec = Flat.str(s, aout + "outPutDecodec");
            t.outRateHz = Flat.pos(s, aout + "outPutSampleRate");
            Double mqa = Flat.pos(s, aout + "outPutMqaType");
            t.mqaOut = (t.outCodec != null && t.outCodec.toUpperCase(Locale.US).contains("MQA")) || mqa != null;
        }
        t.ifaceRaw = Flat.str(s, vol + "volumeTag");
        t.iface = iface(t.ifaceRaw);
        t.volume = Flat.str(s, vol + "display");
        t.volumeLocked = Flat.yes(s, vol + "isLock");
        t.mute = Flat.yes(s, vol + "isMute");
        t.isDsdOut = Flat.yes(s, vol + "isDsdOut");
        Double area = Flat.num(Flat.str(s, vol + "sacdArea"));
        // Preferred SACD area (Settings, HDMI output), measured on the A6 2026-11-16: 0 = Stereo, 1 = Multichannel.
        t.sacdArea = !t.dsd || area == null ? null
                : area.intValue() == 1 ? "Multichannel" : area.intValue() == 0 ? "Stereo" : null;
        t.eq = Flat.yes(s, "eqActive");
        t.dsp = Flat.yes(s, "dspActive");
        t.sub = Flat.yes(s, "subActive");

        // volume: locked, or 0 dB, or current step == max step
        Double cur = Flat.num(Flat.str(s, vol + "currenttVolume")), max = Flat.num(Flat.str(s, vol + "maxVolume"));
        Double db = t.volume != null && t.volume.toLowerCase(Locale.US).contains("db") ? Flat.num(t.volume) : null;
        t.volumeOk = t.volumeLocked || (db != null && Math.abs(db) < 0.01) || (db == null && cur != null && max != null && cur >= max);

        t.bitrateBps = bps(t.bitrate);
        // compact so the whole badge sentence fits on one line : "24bit/192kHz", "DSD256/11.29MHz"
        if (t.dsd) t.formatShort = dsdName(t.rateHz) + (t.rateHz == null ? "" : "/" + rateCompact(t.rateHz));
        else if (t.rateHz != null) t.formatShort = (t.bits == null ? "" : Math.round(t.bits) + "bit/") + rateCompact(t.rateHz);

        t.computeBadge(outputSettled);
        return t;
    }

    void computeBadge(boolean settled) {
        String in = rateCompact(rateHz);
        String out = outCodec == null ? null : (isDsdCodec(outCodec) ? outCodec.replace(" ", "") : outCodec + " " + rateCompact(outRateHz));
        if (dsd && outCodec != null && isDsdCodec(outCodec)) {
            badge = Badge.DSD_NATIVE; badgeText = "DSD native";
            badgeNote = dsdName(rateHz) + " in → " + outCodec.replace(" ", "") + " out";
        } else if (dsd && outCodec != null) {
            badge = Badge.DSD_TO_PCM; badgeText = "DSD → PCM";
            badgeNote = dsdName(rateHz) + " in → " + out + " out";
        } else if (mqaOut && rateHz != null && outRateHz != null) {
            long x = Math.round(outRateHz / rateHz);
            // same rate in and out = nothing unfolded on the A6: the MQA stream goes to the DAC as is (seen 2026-11-15,
            // USB DAC set to "Decoder and renderer"); "unfolded ×1" would claim an unfold that never happened
            badge = Badge.MQA; badgeText = x > 1 ? "MQA unfolded ×" + x : "MQA to DAC";
            badgeNote = in + " in → " + (x > 1 ? rateCompact(outRateHz) : "MQA " + rateCompact(outRateHz)) + " out";
        } else if (lossy) {
            badge = Badge.LOSSY; badgeText = "Lossy (" + container + ")";
            badgeNote = ownPlayer ? (bitrate == null ? container : container + " · " + bitrate) : playerLabel + " · no lossless path";
        } else if (outCodec != null && outRateHz != null && rateHz != null) {
            boolean same = Math.abs(outRateHz - rateHz) < 1;
            if (same && !eq && !dsp && !volumeOk) {
                badge = Badge.VOLUME; badgeText = "Volume " + (volume == null ? "lowered" : volume.replace("-", "−"));
                badgeNote = "digital volume changes the samples";
            } else if (same && !eq && !dsp) {
                badge = Badge.BIT_PERFECT; badgeText = "Bit-perfect";
                badgeNote = in + " in = " + rateCompact(outRateHz) + " out";
            } else if (!same) {
                badge = Badge.RESAMPLED; badgeText = "Resampled";
                badgeNote = in + " in → " + rateCompact(outRateHz) + " out";
            } else {
                badge = Badge.EQ_ACTIVE; badgeText = eq ? "EQ active" : "DSP active";
                badgeNote = in + " in → " + rateCompact(outRateHz) + " out";
            }
        } else if (!settled) {
            badge = Badge.NONE; badgeText = ""; badgeNote = "";
        } else if (service != null || quality != null) {
            badge = Badge.HIRES_STREAM; badgeText = "Hi-res stream";
            badgeNote = (quality == null ? "" : quality) + (service == null ? "" : " · " + pretty(service));
        } else {
            badge = Badge.NONE; badgeText = ""; badgeNote = "";
        }
    }

    // ---------------------------------------------------------------- formatting helpers (shared with the view)

    static boolean isDsdCodec(String c) { return c != null && c.toUpperCase(Locale.US).startsWith("DSD"); }

    static String dsdName(Double hz) {
        if (hz == null) return "DSD";
        long m = Math.round(hz / 44100.0);
        return (m == 64 || m == 128 || m == 256 || m == 512 || m == 1024) ? "DSD" + m : "DSD";
    }

    /** 192000 -> "192 kHz", 44100 -> "44.1 kHz", 11289600 -> "11.29 MHz". */
    public static String rateText(Double hz, boolean dsd) {
        if (hz == null) return "N/A";
        if (hz >= 1_000_000) return String.format(Locale.US, "%.2f MHz", hz / 1e6);
        double k = hz / 1000.0;
        return (Math.abs(k - Math.round(k)) < 0.05 ? String.valueOf(Math.round(k)) : fmt1(k)) + " kHz";
    }

    /** The badge sentence's compact form: 192000 -> "192kHz", 44100 -> "44.1kHz", 11289600 -> "11.29MHz", null -> "?". */
    public static String rateCompact(Double hz) {
        return hz == null ? "?" : rateText(hz, false).replace(" ", "");
    }

    /** "1.76 Mbps" -> 1760000, "826.04 Kbps" -> 826040, "5.64 Mbps" -> 5640000. */
    static Double bps(String v) {
        Double n = Flat.num(v);
        if (n == null || n <= 0) return null;
        String l = v.toLowerCase(Locale.US);
        if (l.contains("mbps")) return n * 1e6;
        if (l.contains("kbps")) return n * 1e3;
        return n;
    }

    static String bpsText(double b) {
        return b >= 1e6 ? fmt1(b / 1e6) + " Mbps" : fmt1(b / 1e3) + " kbps";
    }

    static String fmt1(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    static Double parseRate(String v) {
        Double n = Flat.num(v);
        if (n == null || n <= 0) return null;
        String l = v.toLowerCase(Locale.US);
        if (l.contains("mhz")) return n * 1e6;
        if (l.contains("khz") || n < 1000) return n * 1000;
        return n;
    }

    static String upper(String v) { return v == null ? null : v.toUpperCase(Locale.US); }

    static String firstUrl(String... vals) {
        for (String v : vals) if (v != null && v.matches("(?i)^https?://.*")) return v;
        return null;
    }

    static String pretty(String label) {
        if (label == null) return null;
        String l = label.trim();
        if (l.equalsIgnoreCase("tidal connect")) return "TIDAL Connect";
        if (l.equalsIgnoreCase("tidal")) return "TIDAL";
        if (l.equalsIgnoreCase("qobuz")) return "Qobuz";
        if (l.equalsIgnoreCase("airplay")) return "AirPlay";
        if (l.equalsIgnoreCase("local")) return null;
        return l;
    }

    static String iface(String tag) {
        if (tag == null) return null;
        switch (tag.toUpperCase(Locale.US)) {
            case "XLRRCA": return "XLR/RCA";
            case "XLR": return "BAL-XLR";
            case "RCA": return "Analog-RCA";
            case "IIS": case "I2S": return "I²S";
            case "USB": return "USB DAC";
            case "SPDIF": case "OPT": case "COAX": return "OPT/COAX";
            case "HDMI": return "HDMI";
            default: return tag;
        }
    }

    public boolean analogPort() {
        return ifaceRaw != null && ifaceRaw.toUpperCase(Locale.US).matches("XLRRCA|XLR|RCA");
    }

    /** "Aja (1977 Original Master · Analogue Productions SACD · DSD64)" -> album "Aja" + tags. */
    static void splitAlbum(Track t, String album) {
        t.album = album;
        if (album == null) return;
        Matcher m = Pattern.compile("^(.*?)\\s*\\(([^()]*)\\)\\s*$").matcher(album);
        if (!m.find()) return;
        t.album = m.group(1);
        for (String p : m.group(2).split("[·|•]")) { String x = p.trim(); if (!x.isEmpty()) t.tags.add(x); }
    }

    /** Session class: Hi-res / CD / DSD / Lossy. */
    public String formatClass() {
        if (dsd) return "dsd";
        if (lossy) return "lossy";
        if ((rateHz != null && rateHz > 48000) || (bits != null && bits > 16)) return "hires";
        return "cd";
    }
}
