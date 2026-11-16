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
        String d = FeedbackLink.details("1.0.3", "DMP-A6", null, "1.6.15", "", "1280 x 720 px", null);
        assertEquals("EverSignal 1.0.3 | model: DMP-A6 | firmware: 1.6.15 | screen: 1280 x 720 px", d);
    }

    @Test public void urlPrefillsOnlyWhatIsKnown() {
        String u = FeedbackLink.url(null, "1.6.15", "EverSignal 1.0.3 | model: DMP-A6");
        assertTrue(u.startsWith(FeedbackLink.FORM + "?usp=pp_url"));
        assertFalse(u.contains("entry." + FeedbackLink.ENTRY_MODEL + "="));
        assertTrue(u.contains("&entry." + FeedbackLink.ENTRY_FIRMWARE + "=1.6.15"));
        assertTrue(u.contains("&entry." + FeedbackLink.ENTRY_DETAILS + "=EverSignal%201.0.3%20%7C%20model%3A%20DMP-A6"));
        assertFalse(u.contains("+"));
    }

    @Test public void modelChoiceWithSpacesIsEncoded() {
        String u = FeedbackLink.url("DMP-A6 (Gen 1)", null, null);
        assertTrue(u.endsWith("&entry." + FeedbackLink.ENTRY_MODEL + "=DMP-A6%20%28Gen%201%29"));
    }
}
