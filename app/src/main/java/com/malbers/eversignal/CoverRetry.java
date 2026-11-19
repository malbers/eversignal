package com.malbers.eversignal;

/**
 * 1.0.7: two owners on 1.0.6 reported covers not loading (2026-11-17). Until now a cover was fetched once per
 * track, so a single slow or failed fetch left the square blank for the whole track. This decides when to try
 * again: a FAILED attempt (timeout, network error, bytes that don't decode) is retried after the delays below;
 * a definite answer (a cover, or the player saying it has none) ends the attempts for that track.
 *
 * Attempts run off the polling thread, so each one carries a generation number: a result that comes back
 * after the track has changed is ignored. Called from two threads, hence synchronized.
 */
final class CoverRetry {
    enum Outcome { OK, NONE, FAIL }

    /** Wait before retry 1, 2, 3. Four attempts in all, then the track keeps no cover. */
    static final long[] DELAYS_MS = { 3000, 8000, 20000 };

    private int gen;
    private int attempts;
    private long dueAt;
    private boolean pending, inFlight;

    /** A new track (cover reference): try at once. */
    synchronized void start(long now) {
        gen++;
        attempts = 0;
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
        } else {
            pending = false;
        }
        return true;
    }

    synchronized int attempts() { return attempts; }
    synchronized boolean pending() { return pending; }
}
