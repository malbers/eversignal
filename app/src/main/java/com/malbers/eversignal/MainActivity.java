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
    private Api api;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        api = new Api(host());
        view = new ScreenView(this, this);
        view.setDetail(prefs().getBoolean("detail", false));
        view.setStayOn(stayOn());
        setContentView(view);
        poller = new Poller(api, new Session(this), snap -> ui.post(() -> view.setSnapshot(snap)));
        Thread t = new Thread(poller, "poller");
        t.setDaemon(true);
        t.start();
        showUpdate();
        checkForUpdate();
    }

    // ---------------------------------------------------------------- update check

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String USER_AGENT = "EverSignal/" + BuildConfig.VERSION_NAME + " ( https://youtube.com/@malbers )";
    private long downloadId = -1;
    private String downloadState; // null, "running", "done", "failed"

    /** At most once a day, ask GitHub for the latest release; the answer is kept for the pill. */
    private void checkForUpdate() {
        long now = System.currentTimeMillis();
        if (now - prefs().getLong("upd_checked", 0) < DAY_MS) return;
        prefs().edit().putLong("upd_checked", now).apply();
        Thread t = new Thread(() -> {
            UpdateCheck.Release r = UpdateCheck.latest(new UpdateCheck.UrlHttp(USER_AGENT));
            if (r == null) return;
            prefs().edit().putString("upd_version", r.version).putString("upd_url", r.url).apply();
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
        if ("running".equals(downloadState)) view.setUpdate("Downloading " + v + "…", ScreenView.UPDATE_BUSY);
        else if ("done".equals(downloadState)) view.setUpdate("Saved · install it from Files › Download", ScreenView.UPDATE_OFFER);
        else if ("failed".equals(downloadState)) view.setUpdate("Download failed · tap to try again", ScreenView.UPDATE_FAILED);
        else view.setUpdate("Update " + v + " available · Download", ScreenView.UPDATE_OFFER);
    }

    /**
     * Saves the APK to the shared Download folder with Android's own download service, so no browser is needed
     * (most Eversolos have none). Only ever runs when the pill is tapped. Falls back to a browser if that service is missing.
     */
    @Override public void update() {
        String v = newerVersion();
        String url = prefs().getString("upd_url", null);
        if (v == null || url == null) return;
        if ("running".equals(downloadState)) return;
        KeepOnTopService.suspended = true;   // the user is about to open Files; don't pull the app back over it
        if ("done".equals(downloadState)) { view.toast("Open Files › Download and tap EverSignal-" + v + ".apk"); return; }
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
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                downloadState = "failed";
                showUpdate();
            }
            return;
        }
        downloadState = "running";
        showUpdate();
        pollDownload(dm);
    }

    private void pollDownload(DownloadManager dm) {
        ui.postDelayed(() -> {
            int status = -1;
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
                if (c != null && c.moveToFirst()) status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            } catch (Exception ignored) { }
            if (status == DownloadManager.STATUS_SUCCESSFUL) downloadState = "done";
            else if (status == DownloadManager.STATUS_FAILED || status == -1) downloadState = "failed";
            else { pollDownload(dm); return; }
            showUpdate();
        }, 500);
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        KeepOnTopService.activityVisible = true;
        KeepOnTopService.suspended = false;
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

    /** Back always turns Stay in front off and leaves, so the app can never trap the screen. */
    @Override public void onBackPressed() {
        if (stayOn()) setStay(false);
        super.onBackPressed();
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
        commands.execute(() -> {
            try {
                Thread.sleep(300);
                api.text(Api.VU);
                for (int i = 0; i < 2; i++) {
                    Thread.sleep(900);
                    java.util.Map<String, String> s = Flat.of(new org.json.JSONObject(api.text(Api.STATE)));
                    if (Flat.yes(s, "everSoloPlayInfo.isVuMode")) break;
                    api.text(Api.VU);
                }
            } catch (Exception ignored) { }
        });
    }

    private void send(String path) {
        commands.execute(() -> { try { api.text(path); } catch (Exception ignored) { } });
    }

    // ---------------------------------------------------------------- stay in front

    private boolean stayOn() { return prefs().getBoolean("stay", false); }

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
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                view.toast("Allow \"Display over other apps\", then tap Stay in front again");
            } catch (Exception e) {
                view.toast("This player has no \"Display over other apps\" setting");
            }
            return;
        }
        setStay(true);
    }
}
