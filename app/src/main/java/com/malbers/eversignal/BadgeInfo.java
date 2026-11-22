package com.malbers.eversignal;

import java.util.ArrayList;
import java.util.List;

/**
 * The panel shown when the badge is tapped (1.0.5): what THIS badge means, and for anything that isn't
 * Bit-perfect, a live checklist of what it takes to get there. Volume comes first in the checklist because
 * a lowered digital volume is the most common reason a clean source isn't Bit-perfect.
 * Wording matches the README's "What the badge means".
 */
final class BadgeInfo {
    private BadgeInfo() {}

    /** 1.0.11, from the Eversolo's own EQ screen. */
    static final String EQ_BYPASS = "EQ is switched on, but the Eversolo bypasses it here: it only applies to its own "
        + "player, stereo PCM below 192 kHz. DSD, multichannel, higher rates and AirPlay/casts go through untouched.";

    static List<String> lines(Track t) {
        List<String> l = new ArrayList<>();
        String note = t.badgeNote == null || t.badgeNote.isEmpty() ? null : t.badgeNote;
        switch (t.badge) {
            case BIT_PERFECT:
                l.add("Bit-perfect: the player passes the music to its output unchanged.");
                if (note != null) l.add(note);
                if (t.eqSkipped()) l.add(EQ_BYPASS);
                l.add("Shown when all of these are true:");
                l.add("•  PCM out at the source's own sample rate (no resampling)");
                l.add("•  EQ off and DSP off");
                l.add("•  Volume at 0 dB or locked (volume passthrough on)");
                // passthrough is full output level: never mention it without the warning
                warn(l);
                break;
            case RESAMPLED:
                l.add("Resampled: the player changes the sample rate on the way out.");
                if (note != null) l.add(note);
                l.add("Usually an upsampling setting or a fixed output rate.");
                checklist(l, t);
                break;
            case EQ_ACTIVE:
                l.add((t.eqApplied() ? "EQ active" : "DSP active") + ": a sound effect is changing the samples.");
                if (note != null) l.add(note);
                checklist(l, t);
                break;
            case VOLUME:
                l.add(t.badgeText + ": digital volume below 0 dB changes the samples.");
                l.add("Everything else is clean, so this is the only thing in the way.");
                checklist(l, t);
                break;
            case DSD_NATIVE:
                l.add("DSD native: the DSD file goes out as DSD, untouched.");
                if (note != null) l.add(note);
                l.add("This is DSD's version of Bit-perfect.");
                if (t.eqSkipped()) l.add(EQ_BYPASS);
                break;
            case DSD_TO_PCM:
                l.add("DSD → PCM: the DSD file is converted to PCM on the way out.");
                if (note != null) l.add(note);
                l.add("To keep it DSD:");
                l.add("•  Set this output's DSD mode to Native, if what it feeds takes DSD");
                volumeLine(l, t);
                break;
            case MQA:
                l.add(t.badgeText + (t.badgeText.startsWith("MQA to DAC")
                    ? ": the MQA stream goes to your DAC as is, for the DAC to unfold."
                    : ": the player unfolds MQA itself, to a higher rate."));
                if (note != null) l.add(note);
                volumeLine(l, t);
                break;
            case LOSSY:
                l.add("Lossy: the source itself is compressed with loss.");
                if (note != null) l.add(note);
                l.add("Nothing further down the chain can bring that back.");
                l.add("To get Bit-perfect:");
                l.add("•  Play a lossless version (FLAC, WAV, ALAC or a lossless stream)");
                volumeLine(l, t);
                break;
            case HIRES_STREAM:
                l.add("Hi-res stream: the service says it's hi-res, but the player");
                l.add("doesn't report what it sends out, so there's no verdict to give.");
                if (note != null) l.add(note);
                volumeLine(l, t);
                break;
            case EXTERNAL_APP:
                l.add("Android app: " + (t.playerLabel == null ? "an app" : t.playerLabel) + " is playing on the Eversolo itself.");
                if (note != null) l.add(note);
                l.add("Its audio goes through Android. The player doesn't reliably report the app's source rate");
                l.add("(with EOS off it reports Android's rate, not the file's), so there's no Bit-perfect verdict here.");
                l.add(t.eosOn() ? "EOS is on: the output rate isn't one Android forces, so the app's own rate is getting through."
                    : "EOS: check it's on (Settings › Playback › Eversolo Original sampling-rate audio engine).");
                if (!t.eosOn()) l.add("EverSignal can't read that switch. With EOS off, Android picks the rate: 192 kHz out of USB,"
                    + " 44.1 kHz out of optical (measured on an A6), whatever the track is. A real 192 kHz track looks the same.");
                l.add("A change to EOS takes effect from the next track.");
                volumeLine(l, t);
                break;
            case UNVERIFIED:
                l.add("No verdict: an .m4a can hold ALAC (lossless), AAC or Dolby (lossy),");
                l.add("and the player's own description of it isn't reliable (a Dolby Atmos file showed as 16-bit stereo).");
                if (note != null) l.add(note);
                l.add("To get a verdict:");
                l.add("•  Turn on File access (Settings), so EverSignal can read the codec from the file");
                volumeLine(l, t);
                break;
            case PAUSED:
                l.add("Paused: press play for the signal path.");
                l.add("While a file is paused, the player reports a stand-in output (PCM 192 kHz),");
                l.add("so EverSignal waits for playback instead of guessing.");
                break;
            default:
                l.add("No verdict yet: the player hasn't reported its output.");
        }
        l.add("Not checked: output bit depth (not reported), and anything after");
        l.add("the player's output (the DAC or amp it feeds).");
        l.add("Tap anywhere to close.");
        return l;
    }

    /** Live checklist for the PCM badges: what is already right, and what to change. Volume first. */
    private static void checklist(List<String> l, Track t) {
        l.add("To get Bit-perfect:");
        volumeLine(l, t);
        boolean same = t.rateHz != null && t.outRateHz != null && Math.abs(t.outRateHz - t.rateHz) < 1;
        l.add(same ? "•  OK: same sample rate in and out"
                   : "•  Fix: turn off upsampling or the fixed output rate, so the rate stays the same");
        boolean eq = t.eqApplied();
        l.add(!eq && !t.dsp ? (t.eqSkipped() ? "•  OK: EQ bypassed, DSP off" : "•  OK: EQ and DSP off")
            : "•  Fix: turn " + (eq && t.dsp ? "EQ and DSP" : eq ? "EQ" : "DSP") + " off");
    }

    /** Drawn in the warning colour by ScreenView.drawInfo (one sentence; the panel wraps it). */
    static final String WARN = "    WARNING: only if your amp or DAC sets the volume. Turn it down first: passthrough is full volume.";

    /** Passthrough is full output level: every line that mentions it is followed by this warning. */
    private static void warn(List<String> l) {
        l.add(WARN);
    }

    private static void volumeLine(List<String> l, Track t) {
        if (t.volumeOk) {
            l.add("•  OK: volume at 0 dB (passthrough)");
            warn(l);
        } else {
            l.add("•  Fix: turn on volume passthrough (0 dB) and use your amp's volume");
            // passthrough is full output level: never suggest it without the warning
            warn(l);
        }
    }
}
