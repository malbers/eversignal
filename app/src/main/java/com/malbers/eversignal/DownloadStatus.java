package com.malbers.eversignal;

import java.util.Locale;

/**
 * 1.0.11: turns Android's DownloadManager status into words a person can act on, and spots a stalled download.
 * Pure Java (the DownloadManager constants are copied, they are fixed public API values) so it is unit tested.
 *
 * Why: a user reported "Download leaves the Download folder empty" (2026-11-19). Until 1.0.11 the app kept only
 * running / done / failed, never the reason, showed it only on the Simple pill (not on Settings, where 1.0.10's
 * Check for updates starts a download), and never gave up on a download that stopped moving. An unfinished
 * download is a hidden temp file on Android 10+, so the folder looks empty.
 */
final class DownloadStatus {
    // android.app.DownloadManager values
    static final int PENDING = 1, RUNNING = 2, PAUSED = 4, SUCCESSFUL = 8, FAILED = 16;
    static final int PAUSED_WAITING_TO_RETRY = 1, PAUSED_WAITING_FOR_NETWORK = 2, PAUSED_QUEUED_FOR_WIFI = 3;
    static final int ERROR_FILE = 1001, ERROR_UNHANDLED_HTTP_CODE = 1002, ERROR_HTTP_DATA = 1004,
        ERROR_TOO_MANY_REDIRECTS = 1005, ERROR_INSUFFICIENT_SPACE = 1006, ERROR_DEVICE_NOT_FOUND = 1007,
        ERROR_CANNOT_RESUME = 1008, ERROR_FILE_ALREADY_EXISTS = 1009;

    /** No new bytes for this long = stalled; the app cancels it and says so. */
    static final long STALL_MS = 60_000;

    private DownloadStatus() {}

    /** Short reason for a failed download: fits the Simple pill and the Settings button. */
    static String failReason(int reason) {
        switch (reason) {
            case ERROR_INSUFFICIENT_SPACE: return "not enough space";
            case ERROR_DEVICE_NOT_FOUND: return "no storage found";
            case ERROR_FILE: case ERROR_FILE_ALREADY_EXISTS: return "couldn't save the file";
            case ERROR_HTTP_DATA: case ERROR_CANNOT_RESUME: return "connection dropped";
            case ERROR_TOO_MANY_REDIRECTS: case ERROR_UNHANDLED_HTTP_CODE: return "GitHub refused it";
            default:
                if (reason >= 400 && reason < 600) return "GitHub said " + reason;
                return "error " + reason;
        }
    }

    /** Why a download is waiting, or null when it is simply moving. */
    static String waitReason(int status, int reason) {
        if (status == PENDING) return "waiting to start";
        if (status != PAUSED) return null;
        switch (reason) {
            case PAUSED_WAITING_FOR_NETWORK: return "waiting for network";
            case PAUSED_QUEUED_FOR_WIFI: return "waiting for Wi-Fi";
            case PAUSED_WAITING_TO_RETRY: return "retrying";
            default: return "paused";
        }
    }

    /** "42%", or "1.2 MB" when the size is unknown, or null before any bytes. */
    static String progress(long bytes, long total) {
        if (total > 0 && bytes >= 0) return Math.min(100, (int) (bytes * 100 / total)) + "%";
        if (bytes > 0) return String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0);
        return null;
    }

    /** One line kept for the feedback QR: "1.0.11 failed 1006", "1.0.11 stalled", "1.0.11 ok". Short on purpose. */
    static String diag(String version, String outcome, int reason) {
        return version + " " + outcome + (reason > 0 ? " " + reason : "");
    }

    /** Watches byte counts and says when a download has stopped moving. */
    static final class Stall {
        private long lastBytes = -1, lastMoveAt;

        Stall(long now) { lastMoveAt = now; }

        /** True once no new bytes have arrived for STALL_MS. */
        boolean stalled(long bytes, long now) {
            if (bytes > lastBytes) { lastBytes = bytes; lastMoveAt = now; return false; }
            return now - lastMoveAt >= STALL_MS;
        }
    }
}
