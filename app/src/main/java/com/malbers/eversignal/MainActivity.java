package com.malbers.eversignal;

import android.app.Activity;
import android.content.Intent;
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
