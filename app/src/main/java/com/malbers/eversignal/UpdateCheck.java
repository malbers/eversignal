package com.malbers.eversignal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Asks GitHub for the latest release, the same check AddMyCoverArt and FixMyRips use. Never throws; null means
 * "nothing to report". Sends nothing but the request itself (and a User-Agent naming the app and version).
 */
public final class UpdateCheck {
    public static final String LATEST = "https://api.github.com/repos/malbers/eversignal/releases/latest";

    private UpdateCheck() {}

    /** One GET; tests swap in a fake. */
    public interface Http {
        /** Returns {code, body} or throws for transport failures. */
        Object[] get(String url) throws IOException;
    }

    public static final class Release {
        public final String version;
        /** Direct APK download, or the release page when no APK is attached. */
        public final String url;

        Release(String version, String url) {
            this.version = version;
            this.url = url;
        }
    }

    public static Release latest(Http http) {
        try {
            Object[] r = http.get(LATEST);
            if ((Integer) r[0] != 200) return null;
            JSONObject j = new JSONObject(new String((byte[]) r[1], StandardCharsets.UTF_8));
            String tag = j.optString("tag_name", "");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            if (version.isEmpty()) return null;
            String url = j.optString("html_url", null);
            JSONArray assets = j.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").toLowerCase().endsWith(".apk")) {
                        url = a.optString("browser_download_url", url);
                        break;
                    }
                }
            }
            return url == null ? null : new Release(version, url);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when {@code remote} (e.g. "1.0.2") is a higher version than {@code local} (e.g. "1.0.1" or "1.0.1-test"). */
    public static boolean isNewer(String remote, String local) {
        int[] a = parts(remote);
        int[] b = parts(local);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String v) {
        String core = v == null ? "" : v.split("[^0-9.]", 2)[0];
        String[] s = core.split("\\.");
        int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) {
            try {
                out[i] = Integer.parseInt(s[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** HttpURLConnection GET that follows redirects; capped at 1 MB (a release description is a few KB). */
    public static final class UrlHttp implements Http {
        private final String userAgent;

        public UrlHttp(String userAgent) { this.userAgent = userAgent; }

        @Override public Object[] get(String url) throws IOException {
            for (int hop = 0; hop < 5; hop++) {
                URL u = new URL(url);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setRequestProperty("User-Agent", userAgent);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(false);
                try {
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                        url = new URL(u, c.getHeaderField("Location")).toString();
                        continue;
                    }
                    InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                    return new Object[]{code, in == null ? new byte[0] : readAll(in)};
                } finally {
                    c.disconnect();
                }
            }
            throw new IOException("too many redirects");
        }

        private static byte[] readAll(InputStream in) throws IOException {
            try (InputStream s = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = s.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 1024 * 1024) throw new IOException("response too large");
                }
                return out.toByteArray();
            }
        }
    }
}
