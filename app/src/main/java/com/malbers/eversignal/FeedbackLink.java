package com.malbers.eversignal;

import java.net.URLEncoder;
import java.util.Locale;

/**
 * Builds the pre-filled link to the EverSignal feedback form, shown as a QR code on the Feedback page.
 * Nothing is sent from the player: the viewer's phone opens the form, and only if they scan it.
 * No identifying values go in the link (no MAC, IP, device ID or name).
 *
 * Entry ids are the form's question ids as decimal numbers (read off the live form 2026-11-16;
 * see hifi/files/eversignal-feedback-form-spec.json in Michael's repo). If a question is replaced,
 * its id changes and that field simply stops pre-filling; the form still opens.
 */
final class FeedbackLink {
    static final String FORM =
        "https://docs.google.com/forms/d/e/1FAIpQLSdaQcFaXOcaRyZr520Lxir_NmPKtZG6aojfJ15XBwSbSB9ffQ/viewform";
    static final String ENTRY_MODEL = "998637866";
    static final String ENTRY_FIRMWARE = "429899019";
    static final String ENTRY_DETAILS = "1423254201";

    /** The form's model choices. Pre-filled only on an exact match, never guessed. */
    static final String[] MODELS = {
        "DMP-A6 (Gen 1)", "DMP-A6 Gen 2", "DMP-A6 Master Edition", "DMP-A8", "DMP-A10", "DMP-T8", "DMP-T10", "Play"
    };

    private FeedbackLink() {}

    /**
     * The form's model choice for what the player reports, or null when there is no exact match.
     * "DMP-A6" alone matches nothing on purpose: it can't tell Gen 1, Gen 2 and Master Edition apart.
     */
    static String modelChoice(String... reported) {
        for (String r : reported) {
            if (r == null) continue;
            String t = r.trim();
            if (t.isEmpty()) continue;
            for (String opt : MODELS) {
                if (opt.equalsIgnoreCase(t) || opt.equalsIgnoreCase("DMP-" + t)) return opt;
            }
        }
        return null;
    }

    /** One line for the "Device details" question. Blank parts are left out. */
    static String details(String appVersion, String model, String disModel, String firmware, String android,
                          String screen, String appArea) {
        StringBuilder b = new StringBuilder("EverSignal ").append(appVersion);
        part(b, "model", model);
        part(b, "display model", disModel);
        part(b, "firmware", firmware);
        part(b, "Android", android);
        part(b, "screen", screen);
        part(b, "app area", appArea);
        return b.toString();
    }

    private static void part(StringBuilder b, String k, String v) {
        if (v != null && !v.trim().isEmpty()) b.append(" | ").append(k).append(": ").append(v.trim());
    }

    static String url(String modelChoice, String firmware, String details) {
        StringBuilder u = new StringBuilder(FORM).append("?usp=pp_url");
        add(u, ENTRY_MODEL, modelChoice);
        add(u, ENTRY_FIRMWARE, firmware);
        add(u, ENTRY_DETAILS, details);
        return u.toString();
    }

    private static void add(StringBuilder u, String entry, String v) {
        if (v == null || v.trim().isEmpty()) return;
        u.append("&entry.").append(entry).append('=').append(enc(v.trim()));
    }

    private static String enc(String v) {
        // Forms reads %20 and + alike; %20 keeps the QR payload unambiguous.
        // (The Charset overload is API 33+; the String one works on the A6's Android 11.)
        try {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    static String sizeText(int w, int h) { return String.format(Locale.US, "%d x %d px", w, h); }
}
