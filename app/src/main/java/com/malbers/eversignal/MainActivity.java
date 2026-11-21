package com.malbers.eversignal;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** EverSignal: a now-playing and signal-path screen for the Eversolo DMP-A6, Simple + Detail. */
public class MainActivity extends Activity implements ScreenView.Actions {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private ScreenView view;
    private Poller poller;
    private Session session;
    private Api api;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        api = new Api(host());
        view = new ScreenView(this, this);
        view.setDownloadDiag(prefs().getString("dl_diag", null));
        view.setDetail(prefs().getBoolean("detail", false));
        view.setStayOn(stayOn());
        view.setFrame(prefs().getBoolean("frame", true));
        setContentView(view);
        session = new Session(this);
        poller = new Poller(api, session, snap -> ui.post(() -> { last = snap; view.setSnapshot(snap); followAlbum(); }));
        Thread t = new Thread(poller, "poller");
        t.setDaemon(true);
        t.start();
        showUpdate();
        checkForUpdate();
        whatsNewAfterUpdate();
    }

    /**
     * 1.0.10 (A1): the first time a new version opens after an UPDATE, show what's new once. A fresh install shows
     * nothing (firstInstallTime == lastUpdateTime). Remembered per versionCode.
     */
    private void whatsNewAfterUpdate() {
        int code = BuildConfig.VERSION_CODE;
        if (prefs().getInt("notes_seen", 0) >= code) return;
        prefs().edit().putInt("notes_seen", code).apply();
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.firstInstallTime == pi.lastUpdateTime) return;
        } catch (Exception e) {
            return;
        }
        // 1.0.12: file access is asked for HERE, after the notes say why (Michael 2026-11-20: set the expectation
        // first, never a bare prompt at launch). Already granted = the plain notes dialog.
        if (canReadFiles()) {
            ui.postDelayed(() -> view.showNotes("Updated to EverSignal " + BuildConfig.VERSION_NAME + ". What's new:",
                ReleaseNotes.all(), null, null), 800);
        } else {
            ui.postDelayed(() -> view.showNotes("Updated to EverSignal " + BuildConfig.VERSION_NAME + ". What's new:",
                ReleaseNotes.all(), "Allow file access", this::askFiles), 800);
        }
    }

    // ---------------------------------------------------------------- update check

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String USER_AGENT = "EverSignal/" + BuildConfig.VERSION_NAME + " ( https://youtube.com/@malbers )";
    private long downloadId = -1;
    private String downloadState; // null, "running", "done", "failed"
    /** 1.0.11: the words after the state: "42%", "waiting for network", "not enough space", "stalled". */
    private String downloadDetail;
    private DownloadStatus.Stall stall;

    /** At most once a day, ask GitHub for the latest release; the answer is kept for the pill. */
    private void checkForUpdate() {
        long now = System.currentTimeMillis();
        if (now - prefs().getLong("upd_checked", 0) < DAY_MS) return;
        prefs().edit().putLong("upd_checked", now).apply();
        Thread t = new Thread(() -> {
            UpdateCheck.Release r = UpdateCheck.latest(new UpdateCheck.UrlHttp(USER_AGENT));
            if (r == null) return;
            prefs().edit().putString("upd_version", r.version).putString("upd_url", r.url)
                .putString("upd_notes", r.notes).apply();
            ui.post(this::showUpdate);
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    /** The newer version on GitHub, or null when this build is current. */
    private String newerVersion() {
        String v = prefs().getString("upd_version", null);
        if (v == null || prefs().getString("upd_url", null) == null) return null;
        return UpdateCheck.isNewer(v, BuildConfig.VERSION_NAME) ? v : null;
    }

    private void showUpdate() {
        String v = newerVersion();
        if (v == null) { view.setUpdate(null, 0); return; }
        String d = downloadDetail == null ? "" : " · " + downloadDetail;
        if ("running".equals(downloadState)) view.setUpdate("Downloading " + v + d, ScreenView.UPDATE_BUSY);
        else if ("done".equals(downloadState)) view.setUpdate("Saved · install it from Files › Storage › Download", ScreenView.UPDATE_OFFER);
        else if ("failed".equals(downloadState)) view.setUpdate("Download failed" + d + " · tap to try again", ScreenView.UPDATE_FAILED);
        else view.setUpdate("Update " + v + " available · Download", ScreenView.UPDATE_OFFER);
        // 1.0.11: the same state on Settings' Check for updates button. 1.0.10 started downloads there but only
        // the Simple pill ever said what happened, and the Eversolo has no notification shade.
        if ("running".equals(downloadState)) view.checkStatus(downloadDetail == null ? "Downloading…" : "Downloading " + downloadDetail, 3000);
        else if ("done".equals(downloadState)) view.checkStatus("Saved to Download", 10000);
        else if ("failed".equals(downloadState)) view.checkStatus("Failed: " + downloadDetail, 10000);
    }

    /**
     * Saves the APK to the shared Download folder with Android's own download service, so no browser is needed
     * (most Eversolos have none). Only ever runs when the pill is tapped. Falls back to a browser if that service is missing.
     */
    @Override public void update() {
        // 1.0.10 (A2): the offer shows what's new in that version first; Download in the dialog starts it
        String v = newerVersion();
        if (v != null && downloadState == null) {
            java.util.List<String> lines = ReleaseNotes.fromMarkdown(prefs().getString("upd_notes", null));
            if (!lines.isEmpty()) {
                view.showNotes("EverSignal " + v + " is available. What's new:", lines, "Download " + v, this::download);
                return;
            }
        }
        download();
    }

    private void download() {
        String v = newerVersion();
        String url = prefs().getString("upd_url", null);
        if (v == null || url == null) return;
        if ("running".equals(downloadState)) return;
        KeepOnTopService.suspended = true;   // the user is about to open Files; don't pull the app back over it
        if ("done".equals(downloadState)) { view.toast("Open Files › Storage › Download and tap EverSignal-" + v + ".apk"); return; }
        DownloadManager dm = getSystemService(DownloadManager.class);
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                .setTitle("EverSignal " + v)
                .setMimeType("application/vnd.android.package-archive")
                .addRequestHeader("User-Agent", USER_AGENT)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "EverSignal-" + v + ".apk");
            downloadId = dm.enqueue(req);
        } catch (Exception e) {
            // Android's download service is missing or switched off on this player: try a browser instead
            saveDiag(DownloadStatus.diag(v, "no-dm", 0));
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                finishDownload("failed", "no download service", null);
            }
            return;
        }
        downloadState = "running";
        downloadDetail = null;
        stall = new DownloadStatus.Stall(System.currentTimeMillis());
        showUpdate();
        pollDownload(dm, v);
    }

    private void pollDownload(DownloadManager dm, String v) {
        ui.postDelayed(() -> {
            int status = -1, reason = 0;
            long bytes = 0, total = -1;
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
                if (c != null && c.moveToFirst()) {
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                }
            } catch (Exception ignored) { }
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                finishDownload("done", null, DownloadStatus.diag(v, "ok", 0));
                view.toast("Saved. Open Files › Storage › Download and tap EverSignal-" + v + ".apk");
                return;
            }
            if (status == DownloadManager.STATUS_FAILED) {
                finishDownload("failed", DownloadStatus.failReason(reason), DownloadStatus.diag(v, "failed", reason));
                return;
            }
            if (status == -1) {   // the download vanished (cancelled, or the service lost it)
                finishDownload("failed", "cancelled", DownloadStatus.diag(v, "gone", 0));
                return;
            }
            if (stall.stalled(bytes, System.currentTimeMillis())) {
                try { dm.remove(downloadId); } catch (Exception ignored) { }
                String why = DownloadStatus.waitReason(status, reason);
                finishDownload("failed", why == null ? "stalled" : "stalled, " + why,
                    DownloadStatus.diag(v, "stalled", status == DownloadManager.STATUS_PAUSED ? reason : 0));
                return;
            }
            String wait = DownloadStatus.waitReason(status, reason);
            downloadDetail = wait != null ? wait : DownloadStatus.progress(bytes, total);
            showUpdate();
            pollDownload(dm, v);
        }, 500);
    }

    /** Ends a download: state for the pill + Settings button, a toast on failure, and the one-line diag for the QR. */
    private void finishDownload(String state, String detail, String diag) {
        downloadState = state;
        downloadDetail = detail;
        if (diag != null) saveDiag(diag);
        if ("failed".equals(state)) view.toast("Download failed: " + detail + ". Get it from github.com/malbers/eversignal");
        showUpdate();
    }

    private void saveDiag(String diag) {
        prefs().edit().putString("dl_diag", diag).apply();
        view.setDownloadDiag(diag);
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        KeepOnTopService.activityVisible = true;
        KeepOnTopService.suspended = false;
        if (poller != null) poller.recheckVerdict();   // 1.0.12-2: EQ/output may have changed while away
        if (stayPending) {
            stayPending = false;
            if (Settings.canDrawOverlays(this) && !stayOn()) setStay(true);   // granted while away: finish the tap
        }
        if (stayOn() && Settings.canDrawOverlays(this)) startForegroundService(new Intent(this, KeepOnTopService.class));
        view.setStayOn(stayOn());
    }

    @Override protected void onPause() {
        super.onPause();
        KeepOnTopService.hiddenAt = System.currentTimeMillis();
        KeepOnTopService.activityVisible = false;
    }

    @Override protected void onDestroy() {
        if (poller != null) poller.stop();
        commands.shutdown();
        super.onDestroy();
    }

    /**
     * 1.0.11: Back just leaves; Stay in front stays as set (Michael kept losing it by switching it on, then pressing
     * Back). No trap: the app only returns after a phone-app or AirPlay start, and the switch turns it off.
     */
    @Override public void onBackPressed() {
        if (view.viewerOpen()) { view.closeViewer(); return; }   // 1.0.12: Back leaves the art viewer first
        super.onBackPressed();
    }

    /** 1.0.12: remote / keyboard left-right step the art viewer. */
    @Override public boolean onKeyDown(int code, android.view.KeyEvent e) {
        if (view.viewerOpen()) {
            if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { view.viewerStep(1); return true; }
            if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { view.viewerStep(-1); return true; }
        }
        return super.onKeyDown(code, e);
    }

    // ---------------------------------------------------------------- album art (1.0.12)

    private volatile Poller.Snapshot last;
    private final ExecutorService artExec = Executors.newSingleThreadExecutor();
    private static final String READ = android.Manifest.permission.READ_EXTERNAL_STORAGE;

    private boolean canReadFiles() { return checkSelfPermission(READ) == android.content.pm.PackageManager.PERMISSION_GRANTED; }

    private static String folderOf(String path) {
        if (!ArtLoader.isLocal(path)) return null;
        File p = new File(path).getParentFile();
        return p == null ? null : p.getPath();
    }

    /**
     * 1.0.12: file access is never asked for bare. After an UPDATE the What's new dialog explains it and carries the
     * "Allow file access" button; on a fresh install (no notes dialog) the first tap on the cover explains it.
     * Declined = EverSignal behaves as 1.0.11; the cover tap explains again, at most once per launch.
     */
    static final String WHY_FILES = "To show the art in your album folders (and lyrics files), EverSignal needs to read your files. It never changes or deletes anything.";
    private boolean explainedThisLaunch, openAfterGrant;

    private void askFiles() { requestPermissions(new String[]{READ}, 12); }

    /**
     * Tap on the cover. The pictures come from the playing file's folder; without file access (or for a stream)
     * the viewer still opens with the cover the player gave us.
     */
    @Override public void openViewer() {
        if (!canReadFiles() && !explainedThisLaunch && ArtLoader.isLocal(last == null ? null : last.playingFrom)) {
            explainedThisLaunch = true;
            java.util.List<String> why = new java.util.ArrayList<>();
            why.add(WHY_FILES);
            view.showNotes("Album art from your folders", why, "Allow file access",
                () -> { openAfterGrant = true; askFiles(); });
            return;
        }
        loadViewer(last, false);
    }

    private void loadViewer(Poller.Snapshot sn, boolean replace) {
        if (sn == null) return;
        String path = sn.playingFrom, folder = folderOf(path);
        android.graphics.Bitmap cover = sn.cover;
        artExec.execute(() -> {
            java.util.List<ArtLoader.Item> items = canReadFiles() ? ArtLoader.collect(path) : new java.util.ArrayList<>();
            ui.post(() -> {
                if (replace && !view.viewerOpen()) return;   // closed while loading
                int maxDim = Math.max(getWindow().getDecorView().getWidth(), getWindow().getDecorView().getHeight());
                view.openViewer(new ArtShow(folder, items, cover, Math.max(720, maxDim), view::postInvalidate));
            });
        });
    }

    /**
     * 1.0.12: how many pictures the playing album has, for the "+N" hint on the Simple cover (Michael 2026-11-20:
     * nothing told you a tap shows more). Counted once per album folder, off the UI thread.
     */
    private String countedFolder;
    private void countArt() {
        if (last == null) return;
        String path = last.playingFrom, f = folderOf(path);
        String key = f == null ? "" : f;
        if (key.equals(countedFolder)) return;
        countedFolder = key;
        if (f == null || !canReadFiles()) { view.setArtCount(0); return; }
        artExec.execute(() -> {
            int n = ArtLoader.collect(path).size();
            ui.post(() -> { if (key.equals(countedFolder)) view.setArtCount(n); });
        });
    }

    /** The viewer follows the music: a track from another album folder reloads its pictures; every track its lyrics. */
    private void followAlbum() {
        countArt();
        if (!view.viewerOpen() || last == null) { lyricsFor = null; return; }
        String f = folderOf(last.playingFrom);
        String shown = view.viewerFolder();
        if (f != null && !f.equals(shown)) loadViewer(last, true);
        loadLyrics(last.playingFrom);
    }

    /** 1.0.12: the playing track's .lrc (Pierre C.), looked up once per track while the full-screen view is open. */
    private String lyricsFor;
    private void loadLyrics(String path) {
        String key = path == null ? "" : path;
        if (key.equals(lyricsFor)) return;
        lyricsFor = key;
        if (!canReadFiles() || !ArtLoader.isLocal(path)) { view.setLyrics(null); return; }
        artExec.execute(() -> {
            Lrc l = ArtLoader.lyrics(path);
            ui.post(() -> { if (key.equals(lyricsFor)) view.setLyrics(l); });
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 12) return;
        countedFolder = null;   // recount now that the folder can be read
        boolean open = openAfterGrant;
        openAfterGrant = false;
        if (!canReadFiles()) { if (open) loadViewer(last, false); return; }   // declined: the player's cover only
        if (poller != null) poller.retryCover();               // a cover the player had none for may be in the folder
        if (view.viewerOpen()) loadViewer(last, true);         // granted: fill the viewer
        else if (open) loadViewer(last, false);                // came from a cover tap: open it now
    }

    @Override public void toggleFrame() {
        boolean on = !prefs().getBoolean("frame", true);
        prefs().edit().putBoolean("frame", on).apply();
        view.setFrame(on);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private android.content.SharedPreferences prefs() { return getSharedPreferences("prefs", MODE_PRIVATE); }

    /** Download/EverSignal/host.txt overrides 127.0.0.1, so the same APK can run on a tablet later. */
    private String host() {
        try {
            File f = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "EverSignal"), "host.txt");
            if (f.isFile()) {
                String h = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (h.matches("[A-Za-z0-9.\\-]{1,64}")) return h;
            }
        } catch (Exception ignored) { }
        return "127.0.0.1";
    }

    // ---------------------------------------------------------------- actions

    @Override public void resetStats() { if (session != null) session.reset(); }

    /** 1.0.10: the same GitHub check as the daily one, on demand. A newer version opens the what's-new offer. */
    @Override public void checkUpdates() {
        view.checkStatus("Checking…", 20000);
        Thread t = new Thread(() -> {
            UpdateCheck.Release r = UpdateCheck.latest(new UpdateCheck.UrlHttp(USER_AGENT));
            ui.post(() -> {
                if (r == null) { view.checkStatus("Couldn't reach GitHub", 1800); return; }
                prefs().edit().putLong("upd_checked", System.currentTimeMillis()).putString("upd_version", r.version)
                    .putString("upd_url", r.url).putString("upd_notes", r.notes).apply();
                showUpdate();
                String v = newerVersion();
                if (v != null) { view.checkStatus(v + " available", 1800); update(); }
                else view.checkStatus("Up to date", 1800);
            });
        }, "update-check-now");
        t.setDaemon(true);
        t.start();
    }

    /** Gear: opens Settings & about; a second tap goes back to where you were (Simple, or the Detail page). */
    private boolean settingsFromSimple;
    @Override public void openSettings() {
        if (view.onSettings()) {
            if (settingsFromSimple) { prefs().edit().putBoolean("detail", false).apply(); view.setDetail(false); }
            view.leaveSettings();
            return;
        }
        settingsFromSimple = !prefs().getBoolean("detail", false);
        view.showPage(ScreenView.FEEDBACK_PAGE);
        if (settingsFromSimple) {
            prefs().edit().putBoolean("detail", true).apply();
            view.setDetail(true);
        }
    }

    @Override public void toggleMode() {
        boolean d = !prefs().getBoolean("detail", false);
        prefs().edit().putBoolean("detail", d).apply();
        view.setDetail(d);
    }

    @Override public void playPause() { send(Api.PLAY_PAUSE); }
    @Override public void next() { send(Api.NEXT); }
    @Override public void previous() { send(Api.PREVIOUS); }

    /** "made by @malbers" on the Simple screen: opens the YouTube channel in whatever browser/app handles it. */
    static final String CHANNEL_URL = "https://www.youtube.com/@malbers";

    @Override public void openChannel() {
        KeepOnTopService.suspended = true;   // the browser is wanted; don't pull the app back over it
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CHANNEL_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            view.toast("youtube.com/@malbers");
        }
    }

    /**
     * changVUDisplay CYCLES the player's screen (menu → song info → VU with info → VU only); one press landed on
     * "song info" on 2026-11-15. So press, then check isVuMode, and press again (max 2 more) until it reports VU.
     */
    @Override public void vu() {
        KeepOnTopService.suspended = true;   // the player's own VU screen is wanted; don't pull back over it
        view.toast("Opening the DMP-A6 VU meter…");
        // 1.0.12: at most two presses, each followed by up to 3 s of waiting for isVuMode (see VuPress: Pierre C.'s
        // player reached VU on press 2 and a third press switched it off again).
        commands.execute(() -> {
            try {
                Thread.sleep(300);
                VuPress vp = new VuPress();
                long until = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < until) {
                    long now = System.currentTimeMillis();
                    Boolean vu = null;
                    if (vp.presses() > 0) {
                        try {
                            vu = Flat.yes(Flat.of(new org.json.JSONObject(api.text(Api.STATE))), "everSoloPlayInfo.isVuMode");
                        } catch (Exception ignored) { }
                    }
                    VuPress.Step step = vp.next(now, vu);
                    if (step == VuPress.Step.DONE) break;
                    if (step == VuPress.Step.PRESS) { api.text(Api.VU); vp.pressed(System.currentTimeMillis()); }
                    Thread.sleep(250);
                }
            } catch (Exception ignored) { }
        });
    }

    /**
     * The Eversolo's EQ app (EQSettingActivity in the player app), where EQ is switched on/off per output.
     * Measured on the A6 2026-11-16 over adb: it is the only EQ/DSP screen the player lets other apps open;
     * the stock player's EQ console (EQGeqConsoleActivity) is not exported. Opening it changes no setting.
     * It reads one extra, "isDSP" (default true = the DSP Config tab); the Functions tab's EQ tile passes false.
     */
    static final String EQ_PKG = "com.zidoo.streamer", EQ_ACTIVITY = "com.zidoo.eq.activity.EQSettingActivity";

    @Override public void eq() {
        KeepOnTopService.suspended = true;   // the player's EQ screen is wanted; don't pull back over it
        try {
            startActivity(new Intent().setClassName(EQ_PKG, EQ_ACTIVITY).putExtra("isDSP", false)   // default true = DSP Config
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            KeepOnTopService.suspended = false;
            view.toast("This player's EQ screen can't be opened from here");
        }
    }

    private void send(String path) {
        commands.execute(() -> { try { api.text(path); } catch (Exception ignored) { } });
    }

    // ---------------------------------------------------------------- stay in front

    private boolean stayOn() { return prefs().getBoolean("stay", false); }
    /** 1.0.12: the Stay in front switch was tapped and sent the user to Android's overlay permission screen. */
    private boolean stayPending;

    private void setStay(boolean on) {
        prefs().edit().putBoolean("stay", on).apply();
        Intent svc = new Intent(this, KeepOnTopService.class);
        if (on) startForegroundService(svc); else stopService(svc);
        view.setStayOn(on);
    }

    @Override public void toggleStay() {
        if (stayOn()) { setStay(false); return; }
        if (!Settings.canDrawOverlays(this)) {
            try {
                // 1.0.12: remember the tap, so coming back with the permission granted switches it on (Michael
                // 2026-11-20: it stayed off after approving, and needed a second tap nobody expects)
                stayPending = true;
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                view.toast("Allow \"Display over other apps\", then come back");
            } catch (Exception e) {
                view.toast("This player has no \"Display over other apps\" setting");
            }
            return;
        }
        setStay(true);
    }
}
