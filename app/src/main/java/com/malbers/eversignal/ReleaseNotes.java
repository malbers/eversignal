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

    /** 1.0.12: the last bullet of 1.0.12, right above the "Allow file access" button in the update dialog. */
    static final String WHY_FILES_NOTE = "One new permission: EverSignal asks to read your files, so it can show the art in your album folders and your lyrics. It only reads. It never changes or deletes anything.";

    /** {version, date, bullet...}, newest first. Add the new release at the top on every version bump. */
    static final String[][] NOTES = {
        {"1.0.16", "2026-11-27",
            "Dynamic range follows along! While an album is measuring, the screen scrolls with the results so the newest track is always in view. Scroll up to read earlier tracks and it waits; scroll back down and it follows again.",
            "Lyrics you can jump around in! Drag the lyrics (or use the mouse wheel or the Up and Down arrows) to read ahead or back without the music moving; after a few seconds they glide back to where you are. Tap any line of synced lyrics to jump straight to it, and a thin progress bar under the lyrics lets you tap anywhere in the song. It fades away after a few seconds so full screen stays clean; touch it to bring it back. Thanks to Pierre C.",
            "Tap to skip ahead! Tap anywhere on the progress bar on the Simple screen to jump to that moment, just like the Eversolo's own player.",
            "Smaller fixes: an MP2 file now says DR isn't available for MP2, instead of \"files on the Eversolo only\"."},
        {"1.0.15", "2026-11-27",
            "SACD ISOs, each with its own art! When several ISOs share a folder, every album now shows only its own cover (\"<ISO name>.jpg\") and its own extras from Scans/<ISO name>/, the layout AddMyCoverArt 1.1.2 saves. An ISO alone in its folder works as before.",
            "Full-Screen Album Mode, truly full screen! Once the slideshow turns the page, the stats line fades away and the picture gets the whole screen. Swipe, or a new track, and it comes back until the next page turn. Lyrics keep it.",
            "Back goes back! Back now steps out one screen at a time: Detail to Simple, Settings to where you opened it, and only Back from Simple leaves EverSignal.",
            "Dynamic range, tidier! The Measure buttons sit right under the results, the explanation moved behind an Info button (with where saved logs go), a track you measured on its own shows in the album list straight away, and the \"Saved\" message stays up long enough to read. Thanks to Pierre C.",
            "Your DR results survive a reinstall! EverSignal keeps a copy of its measurements in Download › EverSignal and takes them back after a fresh install.",
            "Smaller fixes: DTS tracks show as Lossy, never Bit-perfect (thanks to Pierre C.); Dynamic range on an SACD ISO says it isn't available for DSD yet, instead of \"files on the Eversolo only\"; the Device panel's output line now says it's the Eversolo's own description (\"HDMI, per the Eversolo\")."},
        {"1.0.14", "2026-11-25",
            "Sound styles, caught! On firmware 1.6.40 and later, a sound style (EVS) changes your music, so EverSignal no longer calls it Bit-perfect: you see an amber Sound style on, it leads the Processing column, and an EVS on/off line sits under Sub. Older firmware is unaffected.",
            "SACD ISO, done right! ISO tracks show as DSD native, with 2&6 ch and DST64 just like the Eversolo's own screen, and the badge explains both. Thanks to Pierre C.",
            "Apple Music, in full! The Apple Music app's cover shows up, and its own quality, like Hi-Res Lossless, appears next to the badge. With EOS on you also see the track's sample rate.",
            "Beta builds! A new Beta updates switch in Settings lets you try new features a few days before everyone else. Betas show up in the same Update pill. It's off by default, so nothing changes unless you switch it on.",
            "Install updates in one tap! The Update pill now downloads and installs the new version itself. The first time, Android asks you to allow EverSignal to install apps.",
            "Lyrics, calmer! Lyrics stay on from track to track, say \"No lyrics\" when a track has none, and sit on a soft gradient in the album's own colour. The button says COVER while lyrics are up, so the way back is obvious. Thanks to Pierre C.",
            "Dynamic Range for Library plays! Albums played from the Library now measure too, EverSignal tells you why a file can't be measured (Dolby, DSD), and Save to Download keeps a foobar-style log. Thanks to Pierre C.",
            "Full-Screen Album Mode follows the track! In a folder that mixes albums, the picture now changes with each track, and long titles scroll gently instead of being cut off. Thanks to Václav H.",
            "Smaller fixes: ALAC 24/96 tracks the Eversolo reports as \"0.001 kHz\" now read right; three copies of the same song no longer get mixed up; PCM sent out as DSD says Converted to DSD; a 5.1 track on a stereo output says it's downmixed; a fresh open always starts on Simple."},
        {"1.0.13", "2026-11-22",
            "Dynamic Range! See how much a master was squashed, per track and per album: DR, peak and loudness, measured right on your Eversolo, with the same numbers as foobar's DR Meter. Detail, Track & file, then Measure track or Measure album (needs File access). The track's DR shows on the Simple screen too. Thanks to Pierre C.",
            "Simple Redesign! A cleaner Simple screen: a big verdict badge with the quality next to it, a signal path from Source to Port that turns amber exactly where your music gets changed, and bitrate, file size and input in one row.",
            "New Settings! Everything on one screen in two columns: a Slideshow speed for Full-Screen Album Mode (5 to 60 s), a File access switch, and Send feedback one tap away. File access switch thanks to Pierre C.",
            "EOS, made visible! Playing Apple Music or another app on the Eversolo? EverSignal shows EOS on when the Eversolo's original-sample-rate engine is passing the app's music through, and an amber EOS off? when Android looks to be resampling it to 192 kHz. Tap the badge for how to fix it.",
            "Dolby Atmos files, fixed! Atmos .m4a tracks no longer show as Bit-perfect ALAC. EverSignal reads the real format from the file: E-AC-3, 5.1, Dolby Atmos. Thanks to Pierre C.",
            "Smaller fixes: the quality word in orange; the Eversolo's generic music-note picture counts as no cover; Checking when you come back to EverSignal, instead of an old verdict."},
        {"1.0.12", "2026-11-20",
            "Full-Screen Album Mode! Tap the cover to see every picture for the album: front, back, inlay, booklet scans in a subfolder, even the art tucked inside your files. It flips through them on its own every 10 seconds. Swipe to step through, tap to close. A tiny 1/3 in the corner of the cover tells you there's more to see. Thanks to Václav H.",
            "Lyrics! If a track has an .lrc file with the same name, a LYRICS button appears in Full-Screen Album Mode and shows the words big, in time with the music. Thanks to Pierre C.",
            "Missing covers, fixed! EverSignal now finds your covers itself, straight from the album folder: cover.jpg, folder.jpg or the art inside your files. Multi-disc sets with the cover one folder up work too. No more blank squares or generic music notes. Thanks to Václav H. and Pierre C.",
            "Channels on screen! 24-bit · 2 ch, 1-bit · 5 ch, right on the Simple screen and in Full-Screen Album Mode.",
            "Framed covers! A thin border in a colour picked from the art. Prefer it plain? Switch it off on Settings & about. Thanks to Václav H. for the idea.",
            "VU button, fixed! It goes to the VU meters and stays there, no more bouncing back. Thanks to Pierre C.",
            "Stay in front, smoother! Allow the permission once and it switches on by itself.",
            WHY_FILES_NOTE},
        {"1.0.11", "2026-11-19",
            "Clearer updates! You see the download's progress, on Settings too, and the reason if it fails. A download that stops moving is cancelled after a minute.",
            "EQ, the way the Eversolo really does it! It only applies EQ to its own player, stereo PCM up to 192 kHz. For DSD, multichannel, higher rates and AirPlay or casts, EverSignal now says EQ skipped.",
            "AAC, matched! It shows 16-bit, the same as the Eversolo's own screen.",
            "Stay in front, sticky! It stays on when you leave with Back. Only a tap on the switch itself turns it off.",
            "Easier QR code! Bigger blocks on the Feedback page, quicker to scan.",
            "More patient covers! If the player first says a track has no cover, EverSignal asks again before giving up."},
        {"1.0.10", "2026-11-18",
            "Settings & about page! Tap the gear at the top (tap it again to go back).",
            "Everything in one place! Your version, these release notes, Check for updates, the Stay in front switch, Reset today's listening stats, and the feedback QR code. The swipe pages are back to the five stats pages.",
            "A real Stay in front switch! It used to hide at the bottom of the first Detail page, and it now comes back faster after you start music from the Eversolo phone app or AirPlay.",
            "What's new, built in! After an update, EverSignal shows what's new once. Tapping the Update pill shows it before you download.",
            "Faster covers! They load in the background and retry if the player is slow to send them."},
        {"1.0.9", "2026-11-18",
            "A neater Simple screen! The play controls sit centred on the whole screen, Up next moved under the cover, and the rows have a little more space."},
        {"1.0.8", "2026-11-18",
            "Apple Lossless, fixed! ALAC .m4a files no longer show as Lossy, and hi-res ALAC shows its true rate. Thanks to Nic S.",
            "\"Checking…\" badge! While a new track settles, the badge shows a grey \"Checking…\" instead of a blank.",
            "Centred controls! The play buttons sit centred under the progress bar. Thanks to Bruce M.",
            "Readable bitrate! The Bitrate tile reads in kbps / Mbps."},
        {"1.0.7", "2026-11-17",
            "File browser covers, fixed! Album covers now show for songs started from the file browser, not only from the Library. Thanks to Jaro S. and Giuseppe V."},
        {"1.0.6", "2026-11-17",
            "EQ button! It sits next to VU: one tap opens the Eversolo's EQ screen, and it lights up when EQ is on.",
            "A steadier badge! It only changes once the new verdict has held for two seconds.",
            "Pause, fixed! Pausing a file no longer shows Resampled.",
            "Passthrough warning! Every badge panel that mentions volume passthrough carries a red warning. Passthrough is full volume."},
        {"1.0.5", "2026-11-17",
            "Tap the badge! See what it means on your player right now, with a live checklist when you're not bit-perfect.",
            "SACD area, fixed! It showed Stereo when the player was set to Multichannel."},
        {"1.0.4", "2026-11-16",
            "Wider screens! On the DMP-T10, EverSignal uses the full width. Thanks to Steve C.-H.",
            "Device panel, fixed! It now fits models with more inputs (A6 Master Gen 2). Thanks to René G. C.",
            "Mouse wheel! It scrolls the Detail pages; a sideways scroll changes page."},
        {"1.0.3", "2026-11-16",
            "Feedback page! Scan the QR code with your phone to send a bug, an idea, or \"it works on my model\".",
            "Screen size! The Device page shows it."},
        {"1.0.2", "2026-11-16",
            "Bigger everything! Text and controls you can read standing at the rack. Detail pages scroll."},
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
