package com.malbers.eversignal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 1.0.12: the RATE / DEPTH tile's second line always carries the channel count, stereo included. */
public class ChannelsTest {
    @Test public void depthAndChannels() {
        assertEquals("24-bit · 2 ch", Track.depthChannels(24.0, 2.0));
        assertEquals("1-bit · 5 ch", Track.depthChannels(1.0, 5.0));     // Pierre's DSD64 DSF
        assertEquals("24-bit · 6 ch", Track.depthChannels(24.0, 6.0));   // his 5.1 ALAC
    }

    @Test public void halves() {
        assertEquals("16-bit", Track.depthChannels(16.0, null));          // streams report channels late
        assertEquals("2 ch", Track.depthChannels(null, 2.0));
        assertEquals("", Track.depthChannels(null, null));
        assertEquals("16-bit", Track.depthChannels(16.0, 0.0));
    }
}
