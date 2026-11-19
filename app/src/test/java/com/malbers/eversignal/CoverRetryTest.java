package com.malbers.eversignal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CoverRetryTest {

    @Test public void loadsAtOnceForANewTrack() {
        CoverRetry r = new CoverRetry();
        r.start(1000);
        assertTrue(r.take(1000) > 0);
    }

    @Test public void aCoverEndsTheAttempts() {
        CoverRetry r = new CoverRetry();
        r.start(0);
        int g = r.take(0);
        assertTrue(r.finish(g, CoverRetry.Outcome.OK, 500));
        assertFalse(r.pending());
        assertEquals(-1, r.take(100_000));
    }

    @Test public void playerSaysNoArtIsFinal() {
        CoverRetry r = new CoverRetry();
        r.start(0);
        r.finish(r.take(0), CoverRetry.Outcome.NONE, 500);
        assertFalse(r.pending());
        assertEquals(-1, r.take(100_000));
    }

    @Test public void aFailureRetriesAfterTheDelays() {
        CoverRetry r = new CoverRetry();
        r.start(0);
        r.finish(r.take(0), CoverRetry.Outcome.FAIL, 1000);
        assertEquals(-1, r.take(3999));             // waits 3 s from the failure
        int g = r.take(4000);
        assertTrue(g > 0);
        r.finish(g, CoverRetry.Outcome.FAIL, 4000);
        assertEquals(-1, r.take(11_999));           // then 8 s
        g = r.take(12_000);
        r.finish(g, CoverRetry.Outcome.OK, 12_500);
        assertFalse(r.pending());
        assertEquals(3, r.attempts());
    }

    @Test public void givesUpAfterFourAttempts() {
        CoverRetry r = new CoverRetry();
        r.start(0);
        long t = 0;
        for (int i = 0; i < 4; i++) {
            int g = r.take(t);
            assertTrue("attempt " + (i + 1), g > 0);
            r.finish(g, CoverRetry.Outcome.FAIL, t);
            t += 60_000;
        }
        assertFalse(r.pending());
        assertEquals(-1, r.take(t));
        assertEquals(4, r.attempts());
    }

    @Test public void onlyOneAttemptRunsAtATime() {
        CoverRetry r = new CoverRetry();
        r.start(0);
        assertTrue(r.take(0) > 0);
        assertEquals(-1, r.take(50_000));
    }

    @Test public void aLateResultForTheOldTrackIsIgnored() {
        CoverRetry r = new CoverRetry();
        r.start(0);
        int old = r.take(0);
        r.start(200);                               // track changed while the old fetch ran
        assertFalse(r.finish(old, CoverRetry.Outcome.OK, 300));
        assertTrue(r.pending());
        assertTrue(r.take(300) > 0);                // the new track loads at once
    }
}
