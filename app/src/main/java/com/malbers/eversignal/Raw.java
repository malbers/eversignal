package com.malbers.eversignal;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Prepares raw replies for the stats pages: drops icons/bitmaps and sort noise, strips signed URL queries. */
public final class Raw {
    private Raw() {}

    private static final Pattern DROP = Pattern.compile("(?i).*(icon|bitmap|selecticon|mNativePtr|mDensity|mRecycled|mRequestPremultiplied|mWidth|mHeight).*");
    private static final Pattern QUEUE_DROP = Pattern.compile("(?i)^(pinyin.*|sort.*|randomCoverIndex|hashcode|androidId|infoId|listId|sources|categoryIndex|genreIndex|channels_second)$");

    public static Map<String, String> clean(Map<String, String> m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m == null) return out;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (DROP.matcher(e.getKey()).matches()) continue;
            out.put(e.getKey(), stripQuery(e.getValue()));
        }
        return out;
    }

    public static Map<String, String> cleanQueueItem(Map<String, String> m) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : clean(m).entrySet()) {
            if (QUEUE_DROP.matcher(e.getKey()).matches()) continue;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Signed stream links carry time-limited tokens: show the address, never the query. */
    public static String stripQuery(String v) {
        if (v == null) return null;
        if (!v.matches("(?is)^https?://.*")) return v;
        int q = v.indexOf('?');
        return q < 0 ? v : v.substring(0, q) + "?…";
    }

    public static boolean isBool(String v) { return "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v); }

    /** Epoch milliseconds -> "2026-11-03 14:12"; -1 or 0 -> null. */
    public static String date(String ms) {
        Double d = Flat.num(ms);
        if (d == null || d <= 0) return null;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(d.longValue()));
    }

    public static String bytes(String v) {
        Double d = Flat.num(v);
        if (d == null || d <= 0) return null;
        return String.format(Locale.US, "%,d bytes (%.1f MB)", d.longValue(), d / 1048576.0);
    }

    public static String bps(String v) {
        Double d = Flat.num(v);
        if (d == null || d <= 0) return null;
        return String.format(Locale.US, "%,d bps", d.longValue());
    }

    /** "everSoloPlayInfo.isCanSeek" -> "isCanSeek" (the stats pages show the device's own names). */
    public static String shortKey(String k) {
        int i = k.lastIndexOf('.');
        return i < 0 ? k : k.substring(i + 1);
    }
}
