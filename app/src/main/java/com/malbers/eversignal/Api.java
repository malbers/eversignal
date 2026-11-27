package com.malbers.eversignal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The player's local API (port 9529). Every call this app makes is listed here.
 *
 * Reads are the ones measured on the DMP-A6. The only commands are play/pause, next, previous
 * (the app's transport buttons) and the VU screen switch. Never add an "is it there?" probe:
 * unknown library calls can lock the player's database for minutes, and the play calls act even
 * with no parameters.
 */
public final class Api {
    public static final int PORT = 9529;

    public static final String STATE = "ZidooMusicControl/v2/getState";
    public static final String IO = "ZidooMusicControl/v2/getInputAndOutputList";
    public static final String MODEL = "ZidooControlCenter/getModel";
    public static final String BRIGHTNESS = "SystemSettings/displaySettings/getScreenBrightness";
    public static final String KNOB = "SystemSettings/displaySettings/getKnobBrightness";
    public static final String VU_STYLES = "SystemSettings/displaySettings/getVUModeList";
    public static final String SPECTRUM_STYLES = "SystemSettings/displaySettings/getSpPlayModeList";
    public static String queue(int start, int count) {
        return "ZidooMusicControl/v2/getPlayQueue?start=" + Math.max(0, start) + "&count=" + count;
    }
    public static String image(String trackId) {
        return "ZidooMusicControl/v2/getImage?id=" + trackId + "&musicType=0&type=0&target=16";
    }

    /**
     * The active output's settings page, at the address the player itself reports (outputInfo.setUrl), e.g.
     * /SystemSettings/audioSettings/getUsbDacOutputOption. Only a plain "get" address is ever called; anything
     * else (a "set" address, a query) returns null and is not called. Read-only, measured 2026-11-15.
     */
    public static String outputOptions(String setUrl) {
        return setUrl != null && setUrl.matches("^/SystemSettings/[A-Za-z0-9/]+/get[A-Za-z0-9]+$") ? setUrl : null;
    }

    // commands
    public static final String PLAY_PAUSE = "ZidooMusicControl/v2/playOrPause";
    public static final String NEXT = "ZidooMusicControl/v2/playNext";
    public static final String PREVIOUS = "ZidooMusicControl/v2/playLast";
    /** 1.0.16: jump within the playing track. Probed on fw 1.6.40 (2026-11-27): time in ms, {"status":200}. */
    public static String seek(long ms) { return "ZidooMusicControl/v2/seekTo?time=" + Math.max(0, ms); }
    /** Cycles the player's own screen: menu, song info, VU with info, VU only. */
    public static final String VU = "ZidooMusicControl/v2/changVUDisplay?openType=0";

    private final String host;
    public Api(String host) { this.host = host; }

    public String url(String path) { return "http://" + host + ":" + PORT + "/" + (path.startsWith("/") ? path.substring(1) : path); }

    public String text(String path) throws Exception {
        return new String(bytes(url(path), 4000), "UTF-8");
    }

    /** Raw bytes from any URL (the player, or a streaming service's cover server). */
    public static byte[] bytes(String url, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(readTimeout);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
