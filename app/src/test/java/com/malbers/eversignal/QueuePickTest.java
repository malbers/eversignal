package com.malbers.eversignal;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 1.0.14: an exact id match wins over a title match (BINI: three same-title copies, 2026-11-24). */
public class QueuePickTest {
    private static JSONArray q(String... idTitle) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i < idTitle.length; i += 2)
            a.put(new JSONObject().put("id", idTitle[i]).put("title", idTitle[i + 1]).put("artist", "BINI"));
        return a;
    }

    @Test public void idBeatsAnEarlierSameTitle() throws Exception {
        JSONArray a = q("101", "Unang Kilig", "102", "Unang Kilig", "103", "Unang Kilig");
        assertEquals(1, Poller.pick(a, "102", "Unang Kilig", "BINI", true));
        assertEquals(0, Poller.pick(a, "102", "Unang Kilig", "BINI", false));   // what 1.0.13 picked
    }

    @Test public void titleIsTheFallbackWhenNoIdMatches() throws Exception {
        JSONArray a = q("9001", "Hook", "9002", "Other");
        assertEquals(-1, Poller.pick(a, "735278916", "Hook", "BINI", true));   // TIDAL: state id ≠ queue id
        assertEquals(0, Poller.pick(a, "735278916", "Hook", "BINI", false));
    }

    @Test public void minusOneIdNeverMatches() throws Exception {
        assertEquals(-1, Poller.pick(q("-1", "x"), "-1", "y", null, true));
    }
}
