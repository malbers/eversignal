package com.malbers.eversignal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON reply -> flat "a.b[0].c" -> value map, plus forgiving readers (the player mixes strings and numbers). */
public final class Flat {
    private Flat() {}

    public static Map<String, String> of(Object json) {
        Map<String, String> out = new LinkedHashMap<>();
        walk("", json, out);
        return out;
    }

    private static void walk(String p, Object v, Map<String, String> out) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); walk(p.isEmpty() ? k : p + "." + k, o.opt(k), out); }
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) walk(p + "[" + i + "]", a.opt(i), out);
        } else {
            out.put(p, v == null || v == JSONObject.NULL ? "" : String.valueOf(v));
        }
    }

    /** Non-empty, non-"null" string, else null. */
    static String str(Map<String, String> m, String key) {
        String v = m == null ? null : m.get(key);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() || "null".equals(v) ? null : v;
    }

    /** First number in the value ("1,44 Mbps" -> 1.44, "192000" -> 192000); null if none or zero-ish when asked. */
    static Double num(String v) {
        if (v == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)?").matcher(v);
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group().replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    static Double pos(Map<String, String> m, String key) {
        Double d = num(str(m, key));
        return d == null || d <= 0 ? null : d;
    }

    static boolean yes(Map<String, String> m, String key) {
        String v = str(m, key);
        return v != null && ("true".equalsIgnoreCase(v) || "1".equals(v));
    }
}
