package com.malbers.eversignal;

/**
 * 1.0.15: Back steps out one level (Michael 2026-11-25: on Simple → Detail, Back went to the Home screen; "it should
 * go to simple first"). Full-Screen Album Mode closes, then an open dialog, then Feedback, then Settings goes back to
 * the screen it was opened from, Detail goes to Simple, and only Back from Simple leaves the app. Pure Java.
 */
final class BackStep {
    private BackStep() {}

    enum Step { CLOSE_VIEWER, CLOSE_DIALOG, CLOSE_FEEDBACK, LEAVE_SETTINGS, TO_SIMPLE, LEAVE_APP }

    static Step of(boolean viewer, boolean dialog, boolean feedback, boolean settings, boolean detail) {
        if (viewer) return Step.CLOSE_VIEWER;
        if (dialog) return Step.CLOSE_DIALOG;
        if (feedback) return Step.CLOSE_FEEDBACK;
        if (settings) return Step.LEAVE_SETTINGS;
        if (detail) return Step.TO_SIMPLE;
        return Step.LEAVE_APP;
    }
}
