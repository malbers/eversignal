package com.malbers.eversignal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayerPlaceholderTest {
    /** A synthetic placeholder: vertical gradient from top to bottom colour, light note stems. */
    private static PlayerPlaceholder.Pixels fake(int size, int top, int bottom, boolean note, int noiseAt) {
        return new PlayerPlaceholder.Pixels() {
            public int width() { return size; }
            public int height() { return size; }
            public int get(int x, int y) {
                if (note && ((x >= 450 && x <= 483 && y >= 420 && y <= 680) || (x >= 680 && x <= 713 && y >= 360 && y <= 630)))
                    return 0xFFE4AEA2;
                if (x == noiseAt) return 0xFF103050;   // a column of real artwork
                float f = y / (float) (size - 1);
                int r = Math.round(((top >> 16) & 0xFF) * (1 - f) + ((bottom >> 16) & 0xFF) * f);
                int g = Math.round(((top >> 8) & 0xFF) * (1 - f) + ((bottom >> 8) & 0xFF) * f);
                int b = Math.round((top & 0xFF) * (1 - f) + (bottom & 0xFF) * f);
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        };
    }

    @Test public void orangeRedPlaceholder() { assertTrue(PlayerPlaceholder.is(fake(1080, 0xFFD87766, 0xFFBD4125, true, -1))); }
    @Test public void purplePlaceholder() { assertTrue(PlayerPlaceholder.is(fake(1080, 0xFF9C7AD8, 0xFF5A3A9E, true, -1))); }
    @Test public void otherSizeIsACover() { assertFalse(PlayerPlaceholder.is(fake(1000, 0xFFD87766, 0xFFBD4125, true, -1))); }
    @Test public void noNoteIsACover() { assertFalse(PlayerPlaceholder.is(fake(1080, 0xFFD87766, 0xFFBD4125, false, -1))); }
    @Test public void flatColourIsACover() { assertFalse(PlayerPlaceholder.is(fake(1080, 0xFF808080, 0xFF808080, true, -1))); }
    @Test public void artworkInARowIsACover() { assertFalse(PlayerPlaceholder.is(fake(1080, 0xFFD87766, 0xFFBD4125, true, 930))); }
}
