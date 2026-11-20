package com.malbers.eversignal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedbackLinkTest {

    @Test public void exactModelMatches() {
        assertEquals("DMP-A8", FeedbackLink.modelChoice("DMP-A8"));
        assertEquals("DMP-T10", FeedbackLink.modelChoice("dmp-t10"));
        assertEquals("DMP-T10", FeedbackLink.modelChoice("T10"));
        assertEquals("Play", FeedbackLink.modelChoice("Play"));
    }

    @Test public void ambiguousA6IsNotGuessed() {
        assertNull(FeedbackLink.modelChoice("DMP-A6"));
        assertNull(FeedbackLink.modelChoice("A6"));
    }

    @Test public void displayModelWinsWhenItMatches() {
        assertEquals("DMP-A6 Master Edition", FeedbackLink.modelChoice("DMP-A6 Master Edition", "DMP-A6"));
        assertEquals("DMP-T8", FeedbackLink.modelChoice(null, " DMP-T8 "));
        assertNull(FeedbackLink.modelChoice(null, "", "  "));
    }

    @Test public void detailsSkipBlankParts() {
        String d = FeedbackLink.details("1.0.3", "DMP-A6", null, "1.6.15", "", "1280 x 720 px, 213 dpi", null);
        assertEquals("EverSignal 1.0.3 | model DMP-A6 | screen 1280x720 213dpi", d);
    }

    @Test public void urlPrefillsOnlyWhatIsKnown() {
        String u = FeedbackLink.url(null, "1.6.15", "EverSignal 1.0.3 | model: DMP-A6");
        assertTrue(u.startsWith(FeedbackLink.FORM + "?usp=pp_url"));
        assertFalse(u.contains("entry." + FeedbackLink.ENTRY_MODEL + "="));
        assertTrue(u.contains("&entry." + FeedbackLink.ENTRY_FIRMWARE + "=1.6.15"));
        assertTrue(u.contains("&entry." + FeedbackLink.ENTRY_DETAILS + "=EverSignal+1.0.3+%7C+model%3A+DMP-A6"));
    }

    @Test public void modelChoiceWithSpacesIsEncoded() {
        String u = FeedbackLink.url("DMP-A6 (Gen 1)", null, null);
        assertTrue(u.endsWith("&entry." + FeedbackLink.ENTRY_MODEL + "=DMP-A6+%28Gen+1%29"));
    }

    @Test public void detailsDropDuplicateDisplayModelAndAddUpdate() {
        String d = FeedbackLink.details("1.0.11", "DMP-A6", "DMP-A6", "1.6.15", "11", null, "1776 x 1080 px", "1.0.11 failed 1006");
        assertEquals("EverSignal 1.0.11 | model DMP-A6 | Android 11 | app 1776x1080 | update 1.0.11 failed 1006", d);
    }

    /** Q2 (2026-11-19): a full real-world link (with an update line) must fit QR version 12 at level L, 65x65 modules; 1.0.10 was 81x81. */
    @Test public void fullLinkStaysShort() {
        String d = FeedbackLink.details("1.0.11", "DMP-A6", "DMP-A6 Master Gen 2", "v1.6.15", "11",
            "1920 x 1080 px, 320 dpi", "1776 x 1080 px", "1.0.11 failed 1006");
        String u = FeedbackLink.url("DMP-A6 Master Edition", "v1.6.15", d);
        assertTrue(u.length() + " chars", u.length() <= 367);
    }
}
