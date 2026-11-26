package com.malbers.eversignal;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ArtFilesTest {
    private static ArtFiles.Entry f(String n) { return new ArtFiles.Entry(n, 100); }
    private static ArtFiles.Entry d(String n, ArtFiles.Entry... kids) { return new ArtFiles.Entry(n, new ArrayList<>(Arrays.asList(kids))); }

    /** Václav H.'s layout (2026-11-18/20): Cover.jpg + "<Folder>.jpg" + tracks + a Scan subfolder of booklet pages. */
    @Test public void vaclavLayout() {
        String folder = "Whitesnake = 1978 = Trouble";
        List<ArtFiles.Entry> files = Arrays.asList(
            f("01 Take Me With You.flac"), f(folder + ".jpg"), f("Cover.jpg"), f("02 Love To Keep You Warm.flac"),
            d("Scan", f("10.jpg"), f("2.jpg"), f("1.jpg"), f("notes.txt")));
        List<String> o = ArtFiles.order(folder, files);
        assertEquals(Arrays.asList("Cover.jpg", folder + ".jpg", "Scan/1.jpg", "Scan/2.jpg", "Scan/10.jpg"), o);
        assertTrue(ArtFiles.hasNamedCover(folder, o));
    }

    /** Michael: "sometimes there is more than one JPG in the folder". All of them, cover-like names first. */
    @Test public void severalRootImages() {
        List<String> o = ArtFiles.order("Album", Arrays.asList(
            f("back.jpg"), f("inlay.JPG"), f("folder.jpg"), f("disc.png"), f("track.flac")));
        assertEquals("folder.jpg", o.get(0));
        assertEquals(4, o.size());
        assertEquals(Arrays.asList("back.jpg", "disc.png", "inlay.JPG"), o.subList(1, 4));
    }

    @Test public void coverBeatsFolderBeatsFront() {
        List<String> o = ArtFiles.order("X", Arrays.asList(f("front.jpg"), f("folder.jpg"), f("COVER.JPEG")));
        assertEquals(Arrays.asList("COVER.JPEG", "folder.jpg", "front.jpg"), o);
    }

    /** A subfolder holding audio is another disc, not artwork; hidden folders and files are skipped. */
    @Test public void discFoldersAndHiddenSkipped() {
        List<String> o = ArtFiles.order("Set", Arrays.asList(
            f("cover.jpg"),
            d("CD1", f("01.flac"), f("cover.jpg")),
            d(".thumbnails", f("a.jpg")),
            f(".hidden.jpg"),
            d("Artwork", f("back.jpg"))));
        assertEquals(Arrays.asList("cover.jpg", "Artwork/back.jpg"), o);
    }

    /** Nothing assumes the artwork folder's name ("Scan" was Václav's own choice). */
    @Test public void anyImageOnlySubfolderCounts() {
        List<String> o = ArtFiles.order("A", Arrays.asList(d("Booklet scans", f("p1.jpg")), d("Scans", f("p1.png"))));
        assertEquals(Arrays.asList("Booklet scans/p1.jpg", "Scans/p1.png"), o);
        assertFalse(ArtFiles.hasNamedCover("A", o));
    }

    @Test public void noImages() {
        assertTrue(ArtFiles.order("A", Arrays.asList(f("01.flac"), f("a.cue"), f("log.txt"))).isEmpty());
    }

    @Test public void capped() {
        List<ArtFiles.Entry> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(f(i + ".jpg"));
        assertEquals(ArtFiles.MAX_IMAGES, ArtFiles.order("A", many).size());
    }

    @Test public void naturalOrder() {
        assertTrue(ArtFiles.natural("2.jpg", "10.jpg") < 0);
        assertTrue(ArtFiles.natural("page 02", "page 10") < 0);
        assertTrue(ArtFiles.natural("B.jpg", "a.jpg") > 0);
        assertEquals(0, Integer.signum(ArtFiles.natural("x", "x")));
    }

    /** Disc folders look one level up for the album's art; nothing else does (an album must not pull the artist folder). */
    @Test public void discFolders() {
        assertTrue(ArtFiles.isDiscFolder("CD1"));
        assertTrue(ArtFiles.isDiscFolder("CD 2"));
        assertTrue(ArtFiles.isDiscFolder("Disc 3 - Live"));
        assertTrue(ArtFiles.isDiscFolder("disk_04"));
        assertTrue(ArtFiles.isDiscFolder("SACD1"));
        assertFalse(ArtFiles.isDiscFolder("Abbey Road"));
        assertFalse(ArtFiles.isDiscFolder("CDs I Like"));
        assertFalse(ArtFiles.isDiscFolder("Discovery"));
        assertFalse(ArtFiles.isDiscFolder("The Best (Disc 1)"));
        assertFalse(ArtFiles.isDiscFolder(null));
    }

    // ---------------------------------------------------------------- 1.0.15: SACD ISOs sharing a folder

    private static final String VIOLATOR = "1990 - Violator (DMCD7 2006 SACD Remaster)";
    private static final String ULTRA = "1997 - Ultra (DMCD9 2007 SACD Remaster)";

    /** Michael's Depeche Mode folder (Gen 1, 2026-11-25): 13 ISOs, each "<ISO name>.jpg", extras in Scans/<ISO name>/. */
    private static List<ArtFiles.Entry> depecheMode() {
        return Arrays.asList(
            f("1981 - Speak & Spell (DMCD1 2006 SACD Remaster).iso"), f("1981 - Speak & Spell (DMCD1 2006 SACD Remaster).jpg"),
            f(VIOLATOR + ".iso"), f(VIOLATOR + ".jpg"), f(ULTRA + ".iso"), f(ULTRA + ".jpg"),
            f("folder.jpg"),
            d("Scans",
                d(VIOLATOR, f("booklet-10.jpg"), f("back.jpg"), f("booklet-2.jpg"), f("notes.txt")),
                d("1981 - Speak & Spell (DMCD1 2006 SACD Remaster)", f("back.jpg"))));
    }

    @Test public void severalIsosAreShared() {
        assertTrue(ArtFiles.isoShared(depecheMode()));
        assertTrue(ArtFiles.isIso("A.ISO"));
        assertFalse(ArtFiles.isIso("a.flac"));
    }

    /** Violator: its own cover, then Scans/<ISO name>/ in natural order. Never a neighbour's cover or folder.jpg. */
    @Test public void sharedIsoOwnArtOnly() {
        assertEquals(Arrays.asList(VIOLATOR + ".jpg", "Scans/" + VIOLATOR + "/back.jpg",
                "Scans/" + VIOLATOR + "/booklet-2.jpg", "Scans/" + VIOLATOR + "/booklet-10.jpg"),
            ArtFiles.isoOrder(VIOLATOR + ".iso", depecheMode()));
    }

    /** Ultra: cover only (no Scans/<ISO name>/). */
    @Test public void sharedIsoCoverOnly() {
        assertEquals(Arrays.asList(ULTRA + ".jpg"), ArtFiles.isoOrder(ULTRA + ".iso", depecheMode()));
    }

    /** No own picture: nothing, not the neighbour's. */
    @Test public void sharedIsoWithoutArt() {
        List<ArtFiles.Entry> dir = Arrays.asList(f("A.iso"), f("B.iso"), f("B.jpg"), f("folder.jpg"));
        assertTrue(ArtFiles.isoOrder("A.iso", dir).isEmpty());
        assertEquals(Arrays.asList("B.jpg"), ArtFiles.isoOrder("B.iso", dir));
    }

    /** .jpeg and .png count too, any case; jpg first. Scan/, Scans/ and Artwork/ all hold extras. */
    @Test public void sharedIsoExtensionsAndScanNames() {
        List<ArtFiles.Entry> dir = Arrays.asList(f("A.iso"), f("B.iso"), f("A.PNG"), f("a.jpeg"),
            d("Artwork", d("A", f("1.jpg"))), d("Scan", d("a", f("2.png"))), d("Booklet", d("A", f("3.jpg"))));
        assertEquals(Arrays.asList("a.jpeg", "A.PNG", "Artwork/A/1.jpg", "Scan/a/2.png"), ArtFiles.isoOrder("A.iso", dir));
    }

    /**
     * Billy Joel (Gen 1): The Nylon Curtain loose beside the Piano Man album folder = shared, its extras in
     * Scans/<ISO name>/ (3 pictures). Piano Man alone in its own folder = an ordinary album.
     */
    @Test public void billyJoel() {
        String nc = "Billy Joel - The Nylon Curtain (1982) [SACD, DSD64, 1Bit-2.8MHz Stereo Channel 2.0, (ISO)]";
        List<ArtFiles.Entry> artist = Arrays.asList(f(nc + ".iso"), f(nc + ".jpg"),
            d("Piano Man", f("Billy Joel - Piano Man (1973).iso"), f("folder.jpg"), d("Scan", f("front.jpg"))),
            d("Scans", d(nc, f("back.jpg"), f("disc-01.jpg"), f("disc-02.jpg"))));
        assertTrue(ArtFiles.isoShared(artist));
        assertEquals(Arrays.asList(nc + ".jpg", "Scans/" + nc + "/back.jpg", "Scans/" + nc + "/disc-01.jpg",
            "Scans/" + nc + "/disc-02.jpg"), ArtFiles.isoOrder(nc + ".iso", artist));

        List<ArtFiles.Entry> pianoMan = Arrays.asList(f("Billy Joel - Piano Man (1973).iso"), f("folder.jpg"),
            d("Scan", f("back.jpg"), f("front.jpg")));
        assertFalse(ArtFiles.isoShared(pianoMan));
    }

    /** Picture folders never make an ISO shared (Pixies Doolittle: Artwork/ of scans); other music does. */
    @Test public void whatMakesAnIsoShared() {
        assertFalse(ArtFiles.isoShared(Arrays.asList(f("Doolittle.iso"), f("Doolittle.jpg"),
            d("Artwork", f("1.jpg"), d("inner", f("2.jpg"))), d("Scans", f("3.jpg")), f("Doolittle.cue"))));
        assertTrue(ArtFiles.isoShared(Arrays.asList(f("A.iso"), f("bonus.flac"))));
        assertTrue(ArtFiles.isoShared(Arrays.asList(f("A.iso"), d("Albums", d("B", f("01.dsf"))))));
        assertFalse(ArtFiles.isoShared(Arrays.asList(f("A.iso"), d(".hidden", f("x.flac")))));
        assertFalse(ArtFiles.isoShared(Arrays.asList(f("01.flac"), f("02.flac"))));   // no ISO at all
    }

    @Test public void coverRank() {
        assertEquals(0, ArtFiles.coverRank("Cover.jpg", "A"));
        assertEquals(3, ArtFiles.coverRank("My Album.jpg", "my album"));
        assertEquals(4, ArtFiles.coverRank("cover_front.jpg", "A"));
        assertEquals(8, ArtFiles.coverRank("AlbumArtSmall.jpg", "A"));
        assertEquals(9, ArtFiles.coverRank("back.jpg", "A"));
    }
}
