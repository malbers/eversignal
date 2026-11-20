package com.malbers.eversignal;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.0.10: release notes inside the app. The notes ship in the APK (no network), newest first, written from the
 * GitHub release text. The update offer (A2) shows the NEXT version's notes, taken from the release text the
 * once-a-day update check already downloads, so it adds no request of its own.
 *
 * Dialog lines use two prefixes: "# " = version header, "• " = bullet; anything else is a plain line.
 */
public final class ReleaseNotes {
    private ReleaseNotes() {}

    /** {version, date, bullet...}, newest first. Add the new release at the top on every version bump. */
    static final String[][] NOTES = {
        {"1.0.11", "2026-11-19",
            "Updating is clearer: you see the download's progress, also on Settings, and the reason if it fails. A download that stops moving is cancelled after a minute.",
            "EQ the way the Eversolo really does it: it only applies EQ to its own player, stereo PCM up to 192 kHz. For DSD, multichannel, higher rates and AirPlay or casts, EverSignal now says EQ skipped.",
            "AAC shows 16-bit, the same as the Eversolo's own screen.",
            "Stay in front stays on when you leave with Back. Only the switch turns it off, and only a tap on the switch itself counts.",
            "Bigger QR code blocks on the Feedback page.",
            "Covers: if the player first says a track has no cover, EverSignal asks again before giving up."},
        {"1.0.10", "2026-11-18",
            "New Settings & about page: tap the gear at the top (tap it again to go back).",
            "On it: your version, these release notes, Check for updates, the Stay in front switch, Reset today's listening stats, and the feedback QR code. The swipe pages are back to the five stats pages.",
            "Stay in front is now a real on/off switch (it used to hide at the bottom of the first Detail page), and it comes back faster after you start music from the Eversolo phone app or AirPlay.",
            "After an update, EverSignal shows what's new once. Tapping the Update pill shows what's new before you download.",
            "Covers load in the background and retry if the player is slow to send them."},
        {"1.0.9", "2026-11-18",
            "Neater Simple screen: the play controls sit centred on the whole screen, Up next moved under the cover, and the rows have a little more space."},
        {"1.0.8", "2026-11-18",
            "Fixed: ALAC (Apple Lossless) .m4a files no longer show as Lossy, and hi-res ALAC shows its true rate. Thanks to Nic S.",
            "While a new track settles, the badge shows a grey \"Checking…\" instead of a blank.",
            "Play controls centred under the progress bar. Thanks to Bruce M.",
            "Bitrate tile reads in kbps / Mbps."},
        {"1.0.7", "2026-11-17",
            "Fixed: album covers now show for songs started from the file browser, not only from the Library. Thanks to Jaro S. and Giuseppe V."},
        {"1.0.6", "2026-11-17",
            "EQ button next to VU: one tap opens the Eversolo's EQ screen. It lights up when EQ is on.",
            "Steadier badge: it only changes once the new verdict has held for two seconds.",
            "Fixed: pausing a file no longer shows Resampled.",
            "Every badge panel that mentions volume passthrough carries a red warning. Passthrough is full volume."},
        {"1.0.5", "2026-11-17",
            "Tap the badge to see what it means on your player right now, with a live checklist when you're not bit-perfect.",
            "Fixed: SACD area showed Stereo when the player was set to Multichannel."},
        {"1.0.4", "2026-11-16",
            "Fits wider screens: on the DMP-T10, EverSignal uses the full width. Thanks to Steve C.-H.",
            "Fixed: the Device panel on models with more inputs (A6 Master Gen 2). Thanks to René G. C.",
            "Mouse wheel scrolls the Detail pages; a sideways scroll changes page."},
        {"1.0.3", "2026-11-16",
            "Feedback page (this page): scan the QR code with your phone to send a bug, an idea, or \"it works on my model\".",
            "Device page shows the screen size."},
        {"1.0.2", "2026-11-16",
            "Bigger text and controls, readable standing at the rack. Detail pages scroll."},
    };

    /** Every bundled release as dialog lines, newest first. */
    public static List<String> all() {
        List<String> out = new ArrayList<>();
        for (String[] n : NOTES) {
            out.add("# " + n[0] + "  ·  " + n[1]);
            for (int i = 2; i < n.length; i++) out.add("• " + n[i]);
        }
        return out;
    }

    /**
     * GitHub release text (Markdown) → dialog lines: the bullet points only, with bold, code and link markup
     * removed. Install instructions, signing hashes and "New in" headers are left out. No bullets → the first
     * paragraph. Empty or null → an empty list (the caller then skips the dialog and downloads directly).
     */
    public static List<String> fromMarkdown(String md) {
        List<String> bullets = new ArrayList<>(), para = new ArrayList<>();
        if (md == null) return bullets;
        boolean paraDone = false;
        for (String raw : md.replace("\r", "").split("\n")) {
            String line = raw.trim();
            if (line.startsWith("- ") || line.startsWith("* ")) {
                String t = clean(line.substring(2));
                if (!t.isEmpty()) bullets.add("• " + t);
            } else if (line.isEmpty()) {
                if (!para.isEmpty()) paraDone = true;
            } else if (!paraDone && !line.startsWith("#")) {
                String t = clean(line);
                if (!t.isEmpty() && !t.toLowerCase().startsWith("new in")) para.add(t);
            }
        }
        if (!bullets.isEmpty()) return bullets;
        if (para.isEmpty()) return para;
        List<String> one = new ArrayList<>();
        one.add(String.join(" ", para));
        return one;
    }

    /** Strips Markdown emphasis, inline code and links ([text](url) → text). */
    static String clean(String s) {
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        s = s.replace("**", "").replace("__", "").replace("`", "");
        return s.trim();
    }
}
