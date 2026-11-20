package com.malbers.eversignal;

import static org.junit.Assert.*;

import org.junit.Test;

public class VuPressTest {
    /** Runs the decision over a simulated player; vuFrom = ms after which isVuMode reads true for a given press count. */
    private static int simulate(long[] vuAfterPress) {
        VuPress v = new VuPress();
        long[] pressedAt = new long[3];
        for (long now = 0; now < 8000; now += 250) {
            Boolean vu = null;
            if (v.presses() > 0) {
                int p = v.presses();
                long when = p <= vuAfterPress.length ? vuAfterPress[p - 1] : -1;
                vu = when >= 0 && now - pressedAt[p - 1] >= when;
            }
            VuPress.Step s = v.next(now, vu);
            if (s == VuPress.Step.DONE) break;
            if (s == VuPress.Step.PRESS) { pressedAt[v.presses()] = now; v.pressed(now); }
        }
        return v.presses();
    }

    /** Michael's A6: press 1 lands on VU and reports within half a second. */
    @Test public void fastPlayerOnePress() { assertEquals(1, simulate(new long[]{500})); }

    /** Press 1 lands on VU but reports slowly (2.5 s): still one press, the old code pressed again at 1.2 s. */
    @Test public void slowReportStillOnePress() { assertEquals(1, simulate(new long[]{2500})); }

    /** Pierre's player: press 1 only opens the playback view, press 2 reaches VU and reports late. Never three. */
    @Test public void pierreTwoPressesNeverThree() { assertEquals(2, simulate(new long[]{-1, 2900})); }

    /** isVuMode never turns true (or every read fails): two presses, then give up. */
    @Test public void neverReportsGivesUpAtTwo() { assertEquals(2, simulate(new long[]{-1, -1})); }

    @Test public void firstStepIsAlwaysAPress() { assertEquals(VuPress.Step.PRESS, new VuPress().next(0, null)); }
}
