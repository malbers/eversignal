package com.malbers.eversignal;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

public class ReleaseNotesTest {
    @Test public void bundledNotesStartAtNewestAndReachBack() {
        List<String> all = ReleaseNotes.all();
        assertTrue(all.get(0).startsWith("# 1.0.15"));
        assertTrue(all.stream().anyMatch(l -> l.startsWith("# 1.0.2 ")));
        assertTrue(all.stream().allMatch(l -> l.startsWith("# ") || l.startsWith("• ")));
    }

    @Test public void bundledVersionsAreNewestFirst() {
        for (int i = 1; i < ReleaseNotes.NOTES.length; i++)
            assertTrue(UpdateCheck.isNewer(ReleaseNotes.NOTES[i - 1][0], ReleaseNotes.NOTES[i][0]));
    }

    @Test public void bundledNotesCoverThisBuild() {
        // the top entry must be this build's version (or the release it is a test build for)
        String top = ReleaseNotes.NOTES[0][0];
        assertFalse(UpdateCheck.isNewer(BuildConfig.VERSION_NAME.split("-")[0], top + ".99"));
    }

    @Test public void markdownKeepsBulletsOnly() {
        String md = "**New in 1.0.7**\r\n\r\n- **Fixed:** album covers now show. Thanks to [Jaro](https://x).\r\n"
            + "- Uses `getImage`.\r\n\r\n**Install or update:** on 1.0.1 or later, the Update pill offers it.\r\n"
            + "Signing certificate SHA-256: `351e`";
        assertEquals(List.of("• Fixed: album covers now show. Thanks to Jaro.", "• Uses getImage."), ReleaseNotes.fromMarkdown(md));
    }

    @Test public void markdownWithoutBulletsGivesFirstParagraph() {
        String md = "# EverSignal 2.0\n\nA whole new\nlook.\n\nInstall guide: [here](https://x)";
        assertEquals(List.of("A whole new look."), ReleaseNotes.fromMarkdown(md));
    }

    @Test public void emptyMarkdownGivesNothing() {
        assertTrue(ReleaseNotes.fromMarkdown(null).isEmpty());
        assertTrue(ReleaseNotes.fromMarkdown("").isEmpty());
        assertTrue(ReleaseNotes.fromMarkdown("**New in 1.0.9**\n").isEmpty());
    }
}
