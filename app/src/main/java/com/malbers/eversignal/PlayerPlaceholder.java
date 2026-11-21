package com.malbers.eversignal;

/**
 * 1.0.12-2: recognises the player's generic "no cover" picture so it is treated as no art, not as a cover.
 *
 * Measured on the A6 2026-11-20: for a track without art, getImage may answer 806 at first and then, on the retry,
 * send a 1080x1080 JPEG: a flat top-to-bottom colour gradient with a light music note in the middle. The colour
 * differs per track (purple on T16 Disc 1, orange-red on Cesaria Evora "Angola"), so a fixed hash can't match it.
 * Michael saw "No cover art" and then the note popping in. The shape is fixed, so this checks the shape:
 * exactly 1080 square, every sampled row one flat colour, top and bottom rows different (the gradient),
 * and the note's two stems clearly lighter than the background beside them.
 *
 * Pure Java (pixels through {@link Pixels}) so it runs in unit tests; Poller passes a Bitmap.
 */
final class PlayerPlaceholder {
    private PlayerPlaceholder() {}

    interface Pixels {
        int width();
        int height();
        /** ARGB, as Bitmap.getPixel. */
        int get(int x, int y);
    }

    static final int SIZE = 1080;
    private static final int[] ROWS = { 20, 280, 540, 800, 1060 };
    private static final int[] COLS = { 20, 150, 540, 930, 1060 };
    /** JPEG noise within a flat row measured at 1 per channel; allow some headroom. */
    private static final int FLAT_TOL = 8;
    /** Left stem (466,540) and right stem (696,500) of the note, against the background at (540,540). */
    private static final int[][] STEMS = { { 466, 540 }, { 696, 500 } };
    private static final int STEM_LIFT = 60;   // measured lift about 175 (sum of channels)

    static boolean is(Pixels p) {
        if (p.width() != SIZE || p.height() != SIZE) return false;
        for (int y : ROWS) {
            int first = p.get(COLS[0], y);
            for (int x : COLS) if (diff(first, p.get(x, y)) > FLAT_TOL) return false;
        }
        if (diff(p.get(COLS[0], ROWS[0]), p.get(COLS[0], ROWS[ROWS.length - 1])) < 15) return false;   // no gradient
        int bg = sum(p.get(540, 540));
        for (int[] s : STEMS) if (sum(p.get(s[0], s[1])) - bg < STEM_LIFT) return false;
        return true;
    }

    /** Largest per-channel difference. */
    private static int diff(int a, int b) {
        int d = 0;
        for (int sh = 0; sh <= 16; sh += 8) d = Math.max(d, Math.abs(((a >> sh) & 0xFF) - ((b >> sh) & 0xFF)));
        return d;
    }

    private static int sum(int c) { return ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF); }
}
