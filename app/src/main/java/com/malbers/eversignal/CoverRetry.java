package com.malbers.eversignal;

/**
 * 1.0.7: two owners on 1.0.6 reported covers not loading (2026-11-17). Until now a cover was fetched once per
 * track, so a single slow or failed fetch left the square blank for the whole track. This decides when to try
 * again: a FAILED attempt (timeout, network error, bytes that don't decode) is retried after the delays below;
 * a cover ends the attempts; the player saying it has none is believed on the third ask (1.0.11, see NONE_DELAYS_MS).
 *
 * Attempts run off the polling thread, so each one carries a generation number: a result that comes back
 * after the track has changed is ignored. Called from two threads, hence synchronized.
 */
final class CoverRetry {
    enum Outcome { OK, NONE, FAIL }

    /** Wait before retry 1, 2, 3. Four attempts in all, then the track keeps no cover. */
    static final long[] DELAYS_MS = { 3000, 8000, 20000 };

    /**
     * 1.0.11: "no art" (806) is asked again after these delays before it is believed. Pierre C. (2026-11-19) saw an
     * empty cover on a large 5.1 DSF while the player itself showed art, and Michael saw a slow first cover load on a
     * new DSF: the player may answer "none" before it has pulled the art out of the file. Three asks in all.
     */
    static final long[] NONE_DELAYS_MS = { 5000, 15000 };
    private int noneCount;

    private int gen;
    private int attempts;
    private long dueAt;
    private boolean pending, inFlight;

    /** A new track (cover reference): try at once. */
    synchronized void start(long now) {
        gen++;
        attempts = 0;
        noneCount = 0;
        dueAt = now;
        pending = true;
        inFlight = false;
    }

    /** The generation to load now, or -1 when nothing is due (none pending, one running, or still waiting). */
    synchronized int take(long now) {
        if (!pending || inFlight || now < dueAt) return -1;
        inFlight = true;
        return gen;
    }

    /** Report an attempt. Returns false when it belongs to an older track (ignore its result). */
    synchronized boolean finish(int forGen, Outcome outcome, long now) {
        if (forGen != gen) return false;
        inFlight = false;
        attempts++;
        if (outcome == Outcome.FAIL && attempts <= DELAYS_MS.length) {
            dueAt = now + DELAYS_MS[attempts - 1];
        } else if (outcome == Outcome.NONE && noneCount < NONE_DELAYS_MS.length) {
            dueAt = now + NONE_DELAYS_MS[noneCount++];
        } else {
            pending = false;
        }
        return true;
    }

    synchronized int attempts() { return attempts; }
    synchronized boolean pending() { return pending; }
}
