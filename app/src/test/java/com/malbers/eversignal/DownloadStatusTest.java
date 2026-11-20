package com.malbers.eversignal;

import static org.junit.Assert.*;

import org.junit.Test;

public class DownloadStatusTest {
    @Test public void failReasonsInPlainWords() {
        assertEquals("not enough space", DownloadStatus.failReason(1006));
        assertEquals("connection dropped", DownloadStatus.failReason(1004));
        assertEquals("GitHub said 404", DownloadStatus.failReason(404));
        assertEquals("error 1000", DownloadStatus.failReason(1000));
    }

    @Test public void waitReasons() {
        assertEquals("waiting to start", DownloadStatus.waitReason(DownloadStatus.PENDING, 0));
        assertEquals("waiting for network", DownloadStatus.waitReason(DownloadStatus.PAUSED, 2));
        assertEquals("retrying", DownloadStatus.waitReason(DownloadStatus.PAUSED, 1));
        assertNull(DownloadStatus.waitReason(DownloadStatus.RUNNING, 0));
    }

    @Test public void progressText() {
        assertEquals("42%", DownloadStatus.progress(42, 100));
        assertEquals("1.5 MB", DownloadStatus.progress(1_572_864, -1));
        assertNull(DownloadStatus.progress(0, -1));
    }

    @Test public void diagLineIsShort() {
        assertEquals("1.0.11 failed 1006", DownloadStatus.diag("1.0.11", "failed", 1006));
        assertEquals("1.0.11 stalled", DownloadStatus.diag("1.0.11", "stalled", 0));
    }

    @Test public void stallOnlyAfterAMinuteWithoutBytes() {
        DownloadStatus.Stall s = new DownloadStatus.Stall(0);
        assertFalse(s.stalled(0, 1_000));
        assertFalse(s.stalled(0, 59_000));
        assertTrue(s.stalled(0, 61_000));
        DownloadStatus.Stall m = new DownloadStatus.Stall(0);
        assertFalse(m.stalled(10, 50_000));
        assertFalse(m.stalled(20, 100_000));   // still moving
        assertTrue(m.stalled(20, 160_001));
    }
}
