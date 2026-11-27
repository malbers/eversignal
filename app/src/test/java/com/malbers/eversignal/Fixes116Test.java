package com.malbers.eversignal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 1.0.16: seeking (Simple bar tap, lyric lines, the bar under lyrics). */
public class Fixes116Test {

    private static Track track(String json) throws Exception {
        return Track.from(Flat.of(new JSONObject(json)), true);
    }

    /** The command probed on the Gen 1, fw 1.6.40, 2026-11-27: time in milliseconds. */
    @Test public void seekCommandIsMilliseconds() {
        assertEquals("ZidooMusicControl/v2/seekTo?time=180000", Api.seek(180000));
    }

    @Test public void seekNeverSendsANegativeTime() {
        assertEquals("ZidooMusicControl/v2/seekTo?time=0", Api.seek(-500));
    }

    /** An app the player can't seek (playType 8) gets no seek bar or line taps. */
    @Test public void externalAppThatCantSeekGetsNoSeek() throws Exception {
        assertFalse(track("{\"state\":3,\"playType\":8,\"everSoloPlayInfo\":{\"isCanSeek\":false}}").canSeek);
        assertTrue(track("{\"state\":3,\"playType\":8,\"everSoloPlayInfo\":{\"isCanSeek\":true}}").canSeek);
    }

    /** The Eversolo's own player can always seek, even if the flag is missing. */
    @Test public void ownPlayerCanAlwaysSeek() throws Exception {
        assertTrue(track("{\"state\":3,\"playType\":5}").canSeek);
    }
}
