package com.malbers.eversignal;

import static org.junit.Assert.*;

import org.junit.Test;

/** 1.0.14: Library plays carry only a drive-relative "uri" and a codec name in the queue (seen on the A6, 2026-11-23). */
public class QueueUriTest {
    @Test public void queueCodecNames() {
        assertEquals("ec-3", Poller.fromQueue("EAC3").codec);
        assertEquals("mp4a", Poller.fromQueue("AAC").codec);
        assertEquals("alac", Poller.fromQueue("ALAC").codec);
        assertEquals("ac-3", Poller.fromQueue("ac3").codec);
        assertNull(Poller.fromQueue("DSD"));
        assertNull(Poller.fromQueue(null));
    }

    @Test public void absoluteUrisPassThroughAndJunkIsNull() {
        assertEquals("/storage/FEFD-FF57/a.m4a", Poller.resolveUri("/storage/FEFD-FF57/a.m4a"));
        assertNull(Poller.resolveUri("http://x/a.m4a"));
        assertNull(Poller.resolveUri(null));
        assertNull(Poller.resolveUri("/EverSoloMusic/not-on-this-machine.m4a"));   // no drive has it here
    }
}
