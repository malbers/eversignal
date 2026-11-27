package com.malbers.eversignal;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 1.0.15-beta.3: Dynamic range list + cache backup (Michael + Pierre C., 2026-11-26). */
public class Fixes115b3Test {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Before public void fresh() { DrScan.resetForTest(); }
    @After public void done() { DrScan.resetForTest(); }

    private File track(File dir, String name, String body) throws Exception {
        File f = new File(dir, name);
        Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    // ---------------------------------------------------------------- B1: a track measured alone shows in the album pass

    @Test public void albumPassShowsAlreadyMeasuredTracksAtOnce() throws Exception {
        File music = tmp.newFolder("album");
        track(music, "01 - A.flac", "a");
        File b = track(music, "14 - Count Up.flac", "bb");
        track(music, "17 - IKYFL.flac", "ccc");
        DrScan.init(tmp.newFolder("files"));
        DrScan.put(b, new DrScan.Item(b.getName(), 4.2, 0.0, -5.3));   // "Measure track" on track 14

        List<Integer> sizes = new ArrayList<>();
        final boolean[] stop = {false};
        DrScan.Album a = DrScan.measureAlbum(music, x -> { sizes.add(x.items.size()); if (!x.running) return; stop[0] = true; },
            () -> stop[0], false);
        // the very first progress report already lists track 14 (it used to wait until the pass reached it)
        assertEquals(Integer.valueOf(1), sizes.get(0));
        assertEquals("14 - Count Up.flac", a.items.get(0).name);
        assertEquals(1, a.done);
        assertEquals(3, a.total);
    }

    @Test public void newResultsKeepFileOrder() {
        List<DrScan.Item> items = new ArrayList<>();
        items.add(new DrScan.Item("14 - Count Up.flac", 4, 0, -5));
        items.add(DrScan.slot(items, "01 - A.flac"), new DrScan.Item("01 - A.flac", 8, -1, -12));
        items.add(DrScan.slot(items, "17 - IKYFL.flac"), new DrScan.Item("17 - IKYFL.flac", 4, 0, -6));
        assertEquals("01 - A.flac", items.get(0).name);
        assertEquals("14 - Count Up.flac", items.get(1).name);
        assertEquals("17 - IKYFL.flac", items.get(2).name);
    }

    // ---------------------------------------------------------------- B4: backup in Download, restore on a fresh install

    @Test public void backupIsDueAfterAMeasureAndClearedAfterSaving() throws Exception {
        File music = tmp.newFolder("m");
        File f = track(music, "01.flac", "x");
        DrScan.init(tmp.newFolder("files"));
        assertFalse(DrScan.backupDue());
        DrScan.put(f, new DrScan.Item(f.getName(), 12.6, -6.5, -23.8));
        assertTrue(DrScan.backupDue());
        DrScan.backedUp();
        assertFalse(DrScan.backupDue());
    }

    @Test public void freshInstallTakesResultsBackFromTheBackup() throws Exception {
        File music = tmp.newFolder("m");
        File f = track(music, "01 - Ashokan.flac", "x");
        // first install: measure, then the backup file is written
        DrScan.init(tmp.newFolder("files-old"));
        DrScan.put(f, new DrScan.Item(f.getName(), 12.67, -6.45, -23.84));
        String json = DrScan.toJson();
        File backups = tmp.newFolder("Download-EverSignal");
        Files.write(new File(backups, "dr-cache-backup.json").toPath(), json.getBytes(StandardCharsets.UTF_8));

        // uninstall + reinstall: empty private storage
        DrScan.resetForTest();
        File filesNew = tmp.newFolder("files-new");
        DrScan.init(filesNew);
        assertNull(DrScan.cached(f));
        assertEquals(1, DrScan.restoreIfFresh(backups));
        DrScan.Item back = DrScan.cached(f);
        assertNotNull(back);
        assertEquals(13, back.rounded());
        assertTrue("restored results are written to the private cache", new File(filesNew, "dr-cache.json").isFile());
        // a second call does nothing: the private cache exists now
        assertEquals(0, DrScan.restoreIfFresh(backups));
    }

    @Test public void noRestoreWhenThePrivateCacheExists() throws Exception {
        File music = tmp.newFolder("m");
        File f = track(music, "01.flac", "x");
        File files = tmp.newFolder("files");
        DrScan.init(files);
        DrScan.put(f, new DrScan.Item(f.getName(), 9, -1, -10));   // writes dr-cache.json
        File backups = tmp.newFolder("bk");
        Files.write(new File(backups, "dr-cache-backup.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, DrScan.restoreIfFresh(backups));
    }

    @Test public void newestBackupWinsOverOlderCopies() throws Exception {
        File backups = tmp.newFolder("bk");
        File old = new File(backups, "dr-cache-backup.json");
        File newer = new File(backups, "dr-cache-backup (1).json");
        Files.write(old.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(newer.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        assertTrue(old.setLastModified(1_000_000L));
        assertTrue(newer.setLastModified(2_000_000L));
        new File(backups, "DR Aventine 20261126-1000.txt").createNewFile();   // a saved log is not a backup
        assertEquals(newer, DrScan.newestBackup(backups));
        assertNull(DrScan.newestBackup(tmp.newFolder("empty")));
    }

    @Test public void unreadableBackupRestoresNothing() throws Exception {
        File backups = tmp.newFolder("bk");
        Files.write(new File(backups, "dr-cache-backup.json").toPath(), "not json".getBytes(StandardCharsets.UTF_8));
        DrScan.init(tmp.newFolder("files"));
        assertEquals(0, DrScan.restoreIfFresh(backups));
    }

    // ---------------------------------------------------------------- B2: the explanation behind Info says where saves go

    @Test public void infoPanelExplainsAndNamesTheSaveFolder() {
        String all = String.join("\n", ScreenView.DR_INFO);
        assertEquals("Dynamic range", ScreenView.DR_INFO[0]);
        assertTrue(all.contains("DR = how much the master was compressed"));
        assertTrue(all.contains("Peak = "));
        assertTrue(all.contains("RMS = "));
        assertTrue(all.contains("Download › EverSignal"));
    }
}
