package com.malbers.eversignal;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class UpdateCheckTest {
    private static UpdateCheck.Http answer(int code, String body) {
        return url -> {
            assertEquals(UpdateCheck.LATEST, url);
            return new Object[]{code, body.getBytes(StandardCharsets.UTF_8)};
        };
    }

    @Test public void readsVersionAndApkUrl() {
        String json = "{\"tag_name\":\"v1.0.2\",\"html_url\":\"https://github.com/x/releases/tag/v1.0.2\","
            + "\"assets\":[{\"name\":\"notes.txt\",\"browser_download_url\":\"https://x/notes.txt\"},"
            + "{\"name\":\"EverSignal-1.0.2.apk\",\"browser_download_url\":\"https://x/EverSignal-1.0.2.apk\"}]}";
        UpdateCheck.Release r = UpdateCheck.latest(answer(200, json));
        assertEquals("1.0.2", r.version);
        assertEquals("https://x/EverSignal-1.0.2.apk", r.url);
        assertEquals("", r.notes);
    }

    @Test public void keepsReleaseText() {
        String json = "{\"tag_name\":\"v1.0.10\",\"html_url\":\"https://x\",\"body\":\"- **New:** notes\",\"assets\":[]}";
        assertEquals("- **New:** notes", UpdateCheck.latest(answer(200, json)).notes);
    }

    @Test public void fallsBackToReleasePageWithoutApk() {
        String json = "{\"tag_name\":\"v2.0\",\"html_url\":\"https://github.com/x/releases/tag/v2.0\",\"assets\":[]}";
        assertEquals("https://github.com/x/releases/tag/v2.0", UpdateCheck.latest(answer(200, json)).url);
    }

    @Test public void failuresAreSilent() {
        assertNull(UpdateCheck.latest(answer(404, "{}")));
        assertNull(UpdateCheck.latest(answer(200, "not json")));
        assertNull(UpdateCheck.latest(answer(200, "{\"html_url\":\"https://x\"}")));
        assertNull(UpdateCheck.latest(url -> { throw new IOException("offline"); }));
    }

    @Test public void versionCompare() {
        assertTrue(UpdateCheck.isNewer("1.0.2", "1.0.1"));
        assertTrue(UpdateCheck.isNewer("1.0.2", "1.0.1-updatetest"));
        assertTrue(UpdateCheck.isNewer("1.1", "1.0.9"));
        assertTrue(UpdateCheck.isNewer("1.0.10", "1.0.9"));
        assertFalse(UpdateCheck.isNewer("1.0.1", "1.0.1"));
        assertFalse(UpdateCheck.isNewer("1.0.0", "1.0.1"));
        assertFalse(UpdateCheck.isNewer("1.0", "1.0.0"));
    }

    /** 1.0.14: the beta channel. A release beats its betas; beta.2 beats beta.1; a beta never "updates" a release. */
    @Test public void betaCompare() {
        assertTrue(UpdateCheck.isNewer("1.0.14", "1.0.14-beta.1"));
        assertTrue(UpdateCheck.isNewer("1.0.14-beta.2", "1.0.14-beta.1"));
        assertTrue(UpdateCheck.isNewer("1.0.14-beta.1", "1.0.13"));
        assertFalse(UpdateCheck.isNewer("1.0.14-beta.1", "1.0.14"));
        assertFalse(UpdateCheck.isNewer("1.0.14-beta.1", "1.0.14-beta.1"));
        assertFalse(UpdateCheck.isNewer("1.0.13", "1.0.14-beta.1"));
        assertFalse(UpdateCheck.isNewer("1.0.14", "1.0.14-test"));   // local test builds unchanged
    }

    @Test public void newestPicksTheHighestIncludingBetas() {
        String list = "[{\"tag_name\":\"v1.0.14-beta.1\",\"prerelease\":true,\"html_url\":\"https://b1\"},"
            + "{\"tag_name\":\"v1.0.13\",\"html_url\":\"https://r13\"},"
            + "{\"tag_name\":\"v1.0.15-beta.1\",\"draft\":true,\"html_url\":\"https://d\"}]";
        UpdateCheck.Release r = UpdateCheck.newest(url -> {
            assertEquals(UpdateCheck.ALL, url);   // beta users read the full list, not /latest
            return new Object[]{200, list.getBytes(StandardCharsets.UTF_8)};
        }, true);
        assertEquals("1.0.14-beta.1", r.version);
        assertEquals("https://b1", r.url);
    }
}
