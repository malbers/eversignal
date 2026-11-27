package com.malbers.eversignal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.0.12: which image files in an album folder are album art, and in what order (pure Java, no Android).
 *
 * Why: until 1.0.11 every local cover came from the player's getImage, one picture chosen by the player. Owners
 * keep more than that: Václav H. (2026-11-20) has Cover.jpg + "<Folder>.jpg" + embedded art, plus a "Scan"
 * subfolder of booklet pages; Michael has folders with more than one JPG, or one in the folder and another
 * embedded. And getImage can return nothing for a folder the player shows art for ("=" in the folder name, found
 * by Václav). The queue tells us the playing file's real path, and EverSignal runs on the player, so it reads the
 * folder itself.
 *
 * Order: likely front covers first (cover, folder, front, the folder's own name), then the other images in the
 * album folder, then images in artwork subfolders. A subfolder counts as artwork when it holds images and no
 * audio; one with audio is another disc, not artwork. Nothing assumes the subfolder's name ("Scan" was his own).
 */
final class ArtFiles {
    private ArtFiles() {}

    /** A file or folder as listed on disk. children is null for a file. */
    static final class Entry {
        final String name;
        final long size;
        final List<Entry> children;
        Entry(String name, long size) { this.name = name; this.size = size; this.children = null; }
        Entry(String name, List<Entry> children) { this.name = name; this.size = 0; this.children = children; }
        boolean isDir() { return children != null; }
    }

    private static final Pattern IMAGE = Pattern.compile("(?i).+\\.(jpe?g|png|webp)$");
    private static final Pattern AUDIO = Pattern.compile(
        "(?i).+\\.(flac|wav|aiff?|alac|m4a|mp3|ogg|opus|wma|ape|wv|dsf|dff|iso|aac|mka|cue)$");

    /** Most image files ever shown for one album (a booklet scan set rarely tops 40). */
    static final int MAX_IMAGES = 60;

    static boolean isImage(String name) { return name != null && !name.startsWith(".") && IMAGE.matcher(name).matches(); }
    static boolean isAudio(String name) { return name != null && AUDIO.matcher(name).matches(); }

    /**
     * Rank of a file name as the album's front cover: 0 cover, 1 folder, 2 front, 3 the album folder's own name,
     * 9 anything else. Case-insensitive, extension ignored. ("albumart*" is the Windows Media hidden copy: 8.)
     */
    static int coverRank(String fileName, String folderName) {
        String base = stripExt(fileName).toLowerCase(Locale.ROOT).trim();
        if (base.equals("cover")) return 0;
        if (base.equals("folder")) return 1;
        if (base.equals("front")) return 2;
        if (folderName != null && base.equals(folderName.toLowerCase(Locale.ROOT).trim())) return 3;
        if (base.startsWith("cover") || base.startsWith("front")) return 4;
        if (base.startsWith("albumart")) return 8;
        return 9;
    }

    /** An artwork subfolder: at least one image, no audio, not hidden. */
    static boolean isArtworkFolder(Entry dir) {
        if (!dir.isDir() || dir.name.startsWith(".")) return false;
        boolean img = false;
        for (Entry e : dir.children) {
            if (e.isDir()) continue;
            if (isAudio(e.name)) return false;
            if (isImage(e.name)) img = true;
        }
        return img;
    }

    /**
     * Relative paths ("Cover.jpg", "Scan/01.jpg") of the album's images, in display order, deduplicated by name
     * and capped at MAX_IMAGES. The first entry is the best front-cover candidate, or null-free empty list.
     */
    static List<String> order(String folderName, List<Entry> folder) {
        List<Entry> root = new ArrayList<>();
        List<Entry> dirs = new ArrayList<>();
        for (Entry e : folder) {
            if (e.isDir()) { if (isArtworkFolder(e)) dirs.add(e); }
            else if (isImage(e.name)) root.add(e);
        }
        root.sort(Comparator.<Entry>comparingInt(e -> coverRank(e.name, folderName)).thenComparing((a, b) -> natural(a.name, b.name)));
        dirs.sort((a, b) -> natural(a.name, b.name));
        List<String> out = new ArrayList<>();
        for (Entry e : root) out.add(e.name);
        for (Entry d : dirs) {
            List<Entry> imgs = new ArrayList<>();
            for (Entry e : d.children) if (!e.isDir() && isImage(e.name)) imgs.add(e);
            imgs.sort((a, b) -> natural(a.name, b.name));
            for (Entry e : imgs) out.add(d.name + "/" + e.name);
        }
        return out.size() > MAX_IMAGES ? new ArrayList<>(out.subList(0, MAX_IMAGES)) : out;
    }

    private static final Pattern DISC = Pattern.compile("(?i)^(cd|disc|disk|dvd|sacd)[ _.\\-]*\\d{1,2}\\b.*");

    /**
     * 1.0.12: a disc folder of a multi-disc album ("CD1", "Disc 2", "CD 3 - Live"). Multi-disc sets often keep the
     * art one level up (Album/cover.jpg with Album/CD1, Album/CD2), so a disc folder also looks in its parent.
     * Strict on purpose: only names that START with a disc word and a number, so an album folder never pulls in
     * the artist folder above it.
     */
    static boolean isDiscFolder(String name) { return name != null && DISC.matcher(name.trim()).matches(); }

    /** True when the first image in order() is a real front-cover name, not just "some image in the folder". */
    static boolean hasNamedCover(String folderName, List<String> ordered) {
        return !ordered.isEmpty() && !ordered.get(0).contains("/") && coverRank(ordered.get(0), folderName) <= 4;
    }

    // ---------------------------------------------------------------- 1.0.15: SACD ISOs sharing a folder
    //
    // Michael's Gen 1 (2026-11-25): EverSoloMusic/ISO/Depeche Mode holds 13 ISOs, each with "<ISO name>.jpg" beside
    // it and extras in Scans/<ISO name>/ (AddMyCoverArt 1.1.2's layout). order() took every picture in the folder, so
    // each Depeche Mode album showed all 13 covers, and Scans/<ISO name>/ (two levels down) never showed. Same rule as
    // AddMyCoverArt's Album.sharedIso / FolderSummary.isosShared. An ISO alone in its folder stays an ordinary album.

    static boolean isIso(String name) { return name != null && name.toLowerCase(Locale.ROOT).endsWith(".iso"); }

    /** Folders that hold an album's extra pictures (AddMyCoverArt's ExtraWriter.isScansName). */
    static boolean isScansName(String n) {
        return n.equalsIgnoreCase("scan") || n.equalsIgnoreCase("scans") || n.equalsIgnoreCase("artwork");
    }

    /** Music for the shared test: a cue sheet is not an album of its own. */
    private static boolean isMusic(String n) { return isAudio(n) && !n.toLowerCase(Locale.ROOT).endsWith(".cue"); }

    /** A folder with music in it, directly or one level down (an album, or a folder of albums). */
    static boolean holdsMusic(Entry dir) {
        if (!dir.isDir()) return false;
        for (Entry e : dir.children) if (!e.isDir() && isMusic(e.name)) return true;
        for (Entry e : dir.children) {
            if (!e.isDir() || e.name.startsWith(".")) continue;
            for (Entry g : e.children) if (!g.isDir() && isMusic(g.name)) return true;
        }
        return false;
    }

    /**
     * True when the ISOs in this folder are albums of their own: several ISOs, an ISO beside other music, or beside
     * a subfolder that holds music. Picture folders (Scan, Scans, Artwork, anything without music) don't count.
     * Needs the listing two levels deep (folder / subfolder / its subfolders' files).
     */
    static boolean isoShared(List<Entry> folder) {
        int isos = 0, music = 0;
        for (Entry e : folder) {
            if (e.isDir()) { if (!e.name.startsWith(".") && holdsMusic(e)) return true; }
            else if (isMusic(e.name)) { music++; if (isIso(e.name)) isos++; }
        }
        return isos > 1 || (isos > 0 && music > isos);
    }

    /**
     * A shared ISO's own pictures, as relative paths: "<ISO name>.jpg/.jpeg/.png" beside it (jpg first), then the
     * pictures in Scan|Scans|Artwork/<ISO name>/ in natural order. Never a neighbour's cover, never folder.jpg.
     */
    static List<String> isoOrder(String isoFileName, List<Entry> folder) {
        String base = stripExt(isoFileName);
        List<String> out = new ArrayList<>();
        for (String ext : new String[]{"jpg", "jpeg", "png"}) {
            for (Entry e : folder) if (!e.isDir() && e.name.equalsIgnoreCase(base + "." + ext)) out.add(e.name);
        }
        List<Entry> scans = new ArrayList<>();
        for (Entry e : folder) if (e.isDir() && isScansName(e.name)) scans.add(e);
        scans.sort((a, b) -> natural(a.name, b.name));
        for (Entry s : scans) {
            for (Entry own : s.children) {
                if (!own.isDir() || !own.name.equalsIgnoreCase(base)) continue;
                List<Entry> imgs = new ArrayList<>();
                for (Entry e : own.children) if (!e.isDir() && isImage(e.name)) imgs.add(e);
                imgs.sort((a, b) -> natural(a.name, b.name));
                for (Entry e : imgs) out.add(s.name + "/" + own.name + "/" + e.name);
            }
        }
        return out.size() > MAX_IMAGES ? new ArrayList<>(out.subList(0, MAX_IMAGES)) : out;
    }

    static String stripExt(String name) {
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(0, d) : name;
    }

    private static final Pattern CHUNK = Pattern.compile("(\\d+)|(\\D+)");

    /** Natural order, case-insensitive: "2.jpg" before "10.jpg". */
    static int natural(String a, String b) {
        Matcher ma = CHUNK.matcher(a.toLowerCase(Locale.ROOT)), mb = CHUNK.matcher(b.toLowerCase(Locale.ROOT));
        while (true) {
            boolean ha = ma.find(), hb = mb.find();
            if (!ha || !hb) return ha ? 1 : hb ? -1 : a.compareTo(b);
            String ca = ma.group(), cb = mb.group();
            int c;
            if (ma.group(1) != null && mb.group(1) != null) {
                String ta = ca.replaceFirst("^0+(?=.)", ""), tb = cb.replaceFirst("^0+(?=.)", "");
                c = ta.length() != tb.length() ? Integer.compare(ta.length(), tb.length()) : ta.compareTo(tb);
            } else c = ca.compareTo(cb);
            if (c != 0) return c;
        }
    }
}
