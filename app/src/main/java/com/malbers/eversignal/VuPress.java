package com.malbers.eversignal;

/**
 * 1.0.12: when to press the player's VU button (changVUDisplay), pure Java.
 *
 * Pierre C. (2026-11-20, every control surface): EverSignal → the stock playback view → the VU meters → back to
 * EverSignal, all within about a second. Until 1.0.11 the button pressed up to THREE times, 900 ms apart, while
 * the player's isVuMode still read false. On his player the first press only opens the playback view, the second
 * reaches VU, and isVuMode reports late, so a third press switched VU off again and the player handed the screen
 * back. On Michael's A6 the first press lands on VU and reports in time, which is why it never showed here.
 *
 * Now: at most TWO presses, and after each one wait up to WAIT_MS for the player to say it is in VU mode before
 * deciding anything. A third press never happens.
 */
final class VuPress {
    enum Step { PRESS, WAIT, DONE }

    static final long WAIT_MS = 3000;
    static final int MAX_PRESSES = 2;

    private int presses;
    private long lastPressAt;

    /** vuMode = the player's isVuMode right now (null before the first press or when the read failed). */
    Step next(long now, Boolean vuMode) {
        if (presses == 0) return Step.PRESS;
        if (Boolean.TRUE.equals(vuMode)) return Step.DONE;
        if (now - lastPressAt < WAIT_MS) return Step.WAIT;
        return presses < MAX_PRESSES ? Step.PRESS : Step.DONE;
    }

    void pressed(long now) { presses++; lastPressAt = now; }

    int presses() { return presses; }
}
