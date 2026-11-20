package com.malbers.eversignal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * 1.0.12: the album's art set, read from the playing file's folder on the player (see ArtFiles for why).
 * Read-only: files are opened for reading and nothing is written. Runs off the UI and polling threads.
 * Needs READ_EXTERNAL_STORAGE; without it every list comes back empty and EverSignal behaves as before.
 */
final class ArtLoader {
    private ArtLoader() {}

    /** One picture: a file in the folder, or bytes embedded in the playing file. */
    static final class Item {
        final File file;
        final byte[] data;
        final String label;     // "Cover.jpg", "Scan/03.jpg", "Embedded: front cover"
        Item(File file, String label) { this.file = file; this.data = null; this.label = label; }
        Item(byte[] data, String label) { this.file = null; this.data = data; this.label = label; }
    }

    /** True for a path on the player's own storage (the queue's "path" for library and file-browser play). */
    static boolean isLocal(String path) { return path != null && path.startsWith("/"); }

    /** Every picture for the album the track belongs to, in display order, duplicates removed. */
    static List<Item> collect(String trackPath) {
        List<Item> out = new ArrayList<>();
        if (!isLocal(trackPath)) return out;
        File track = new File(trackPath), dir = track.getParentFile();
        if (dir == null || !dir.canRead()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;

        List<String> ordered = ArtFiles.order(dir.getName(), entries(files));
        List<Item> root = new ArrayList<>(), sub = new ArrayList<>();
        for (String rel : ordered) (rel.contains("/") ? sub : root).add(new Item(new File(dir, rel), rel));

        // 1.0.12: a disc folder (CD1, Disc 2) also shows the album's art one level up, minus the other discs
        List<Item> upRoot = new ArrayList<>(), upSub = new ArrayList<>();
        boolean upNamed = false;
        File parent = dir.getParentFile();
        if (ArtFiles.isDiscFolder(dir.getName()) && parent != null && parent.canRead()) {
            File[] pf = parent.listFiles();
            if (pf != null) {
                List<String> up = ArtFiles.order(parent.getName(), entries(pf));
                upNamed = ArtFiles.hasNamedCover(parent.getName(), up);
                String tag = parent.getName() + "/";
                for (String rel : up) (rel.contains("/") ? upSub : upRoot).add(new Item(new File(parent, rel), tag + rel));
            }
        }
        boolean ownNamed = ArtFiles.hasNamedCover(dir.getName(), ordered);

        List<Item> embedded = new ArrayList<>();
        if (track.canRead()) {
            List<EmbeddedArt.Pic> pics = embedded(track);
            // a lone picture is the cover whatever its type byte says (taggers often write 0 = "other")
            for (EmbeddedArt.Pic p : pics) embedded.add(new Item(p.data, pics.size() == 1 ? "Embedded cover" : "Embedded: " + picType(p.type)));
        }
        // A named cover in the folder leads (it is what the player shows). With none, the album's cover one level up
        // leads (disc folders), else the file's own front cover.
        if (ownNamed) { out.addAll(root); out.addAll(upRoot); out.addAll(embedded); }
        else if (upNamed) { out.addAll(upRoot); out.addAll(root); out.addAll(embedded); }
        else { out.addAll(embedded); out.addAll(root); out.addAll(upRoot); }
        out.addAll(sub);
        out.addAll(upSub);
        return dedupe(out);
    }

    private static List<ArtFiles.Entry> entries(File[] files) {
        List<ArtFiles.Entry> entries = new ArrayList<>();
        for (File f : files) {
            if (f.isDirectory()) {
                File[] sub = f.listFiles();
                List<ArtFiles.Entry> kids = new ArrayList<>();
                if (sub != null) for (File s : sub) kids.add(s.isDirectory() ? new ArtFiles.Entry(s.getName(), new ArrayList<>()) : new ArtFiles.Entry(s.getName(), s.length()));
                entries.add(new ArtFiles.Entry(f.getName(), kids));
            } else entries.add(new ArtFiles.Entry(f.getName(), f.length()));
        }
        return entries;
    }

    static List<EmbeddedArt.Pic> embedded(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            return EmbeddedArt.read(new EmbeddedArt.Src() {
                @Override public long length() throws IOException { return raf.length(); }
                @Override public int read(long pos, byte[] b, int off, int len) throws IOException { raf.seek(pos); return raf.read(b, off, len); }
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static String picType(int t) {
        switch (t) {
            case 3: return "front cover";
            case 4: return "back cover";
            case 5: return "leaflet";
            case 6: return "media";
            case 8: return "artist";
            default: return "picture";
        }
    }

    /** The same picture often sits in the folder AND inside the files: keep the first. Key = size + CRC of 64 KB. */
    private static List<Item> dedupe(List<Item> in) {
        Set<String> seen = new HashSet<>();
        List<Item> out = new ArrayList<>();
        for (Item it : in) {
            String k = key(it);
            if (k == null || seen.add(k)) out.add(it);
        }
        return out;
    }

    private static String key(Item it) {
        CRC32 crc = new CRC32();
        try {
            if (it.data != null) {
                crc.update(it.data, 0, Math.min(it.data.length, 65536));
                return it.data.length + ":" + crc.getValue();
            }
            byte[] b = new byte[65536];
            int n;
            try (FileInputStream in = new FileInputStream(it.file)) { n = in.read(b); }
            if (n > 0) crc.update(b, 0, n);
            return it.file.length() + ":" + crc.getValue();
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes a picture no larger than maxDim on its longest side (booklet scans can be 5000+ px). */
    static Bitmap decode(Item it, int maxDim) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            if (it.data != null) BitmapFactory.decodeByteArray(it.data, 0, it.data.length, o);
            else BitmapFactory.decodeFile(it.file.getPath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return it.data != null ? BitmapFactory.decodeByteArray(it.data, 0, it.data.length, o)
                : BitmapFactory.decodeFile(it.file.getPath(), o);
        } catch (Throwable t) {   // OutOfMemoryError included: skip the picture, never the app
            return null;
        }
    }

    /** 1.0.12: lyrics in "<track name>.lrc" next to the track (name match ignores case), or null. */
    static Lrc lyrics(String trackPath) {
        if (!isLocal(trackPath)) return null;
        File track = new File(trackPath), dir = track.getParentFile();
        if (dir == null) return null;
        String want = Lrc.lrcNameFor(track.getName());
        File f = new File(dir, want);
        if (!f.isFile()) {
            f = null;
            File[] all = dir.listFiles();
            if (all != null) for (File x : all) if (x.getName().equalsIgnoreCase(want)) { f = x; break; }
        }
        if (f == null || f.length() > 512 * 1024) return null;
        try {
            return Lrc.parse(Lrc.decode(java.nio.file.Files.readAllBytes(f.toPath())));
        } catch (Exception e) {
            return null;
        }
    }

    /** The album's best front cover, for when the player's getImage has none. */
    static Bitmap mainCover(String trackPath, int maxDim) {
        for (Item it : collect(trackPath)) {
            // folder-level, parent-folder (disc sets) or embedded art may stand in for the cover; scan pages may not
            if (it.file != null && !it.file.getParentFile().equals(new File(trackPath).getParentFile())
                    && !it.file.getParentFile().equals(new File(trackPath).getParentFile().getParentFile())) break;
            Bitmap b = decode(it, maxDim);
            if (b != null) return b;
        }
        return null;
    }
}
