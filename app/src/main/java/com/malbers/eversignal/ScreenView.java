package com.malbers.eversignal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Draws both modes (Simple and Detail) from the original design and the review rounds that followed.
 * Laid out on the design's 960 x 480 dp canvas and scaled; the rightmost 60 dp is the player's own sidebar.
 */
public final class ScreenView extends View {

    public interface Actions {
        void toggleMode();
        void playPause();
        void next();
        void previous();
        void vu();
        void eq();
        void toggleStay();
        void openChannel();
        void update();
        /** 1.0.10: the gear button, Settings & about (Detail's last page) from either mode. */
        void openSettings();
        /** 1.0.10: GitHub release check right now (same request as the daily one). */
        void checkUpdates();
        /** 1.0.10: clear today's listening stats. */
        void resetStats();
        /** 1.0.12: tap the cover, full-screen album art (every picture in the album folder and the file). */
        void openViewer();
        /** 1.0.12: Settings & about, the cover frame switch. */
        void toggleFrame();
    }

    // 1.0.12: full-screen album art viewer. Flips on its own every SLIDE_MS; a swipe steps; a tap or Back closes.
    /** Michael 2026-11-20: "if you do nothing, then it should just flip through it automatically". Fixed for now. */
    static final long SLIDE_MS = 10_000;
    private ArtShow viewer;
    private int vIdx;
    private long vNextAt, vChangedAt;
    /** 1.0.12: a thin border in a colour taken from the picture (Václav H.'s framing idea, 2026-11-20). */
    private boolean frame = true;
    private final java.util.WeakHashMap<Bitmap, Integer> frameColours = new java.util.WeakHashMap<>();

    public void openViewer(ArtShow a) {
        if (viewer != null) viewer.close();
        viewer = a; vIdx = 0;
        long now = System.currentTimeMillis();
        vNextAt = now + SLIDE_MS; vChangedAt = now;
        invalidate();
    }
    public boolean viewerOpen() { return viewer != null; }
    public String viewerFolder() { return viewer == null ? null : viewer.folder; }
    public void closeViewer() { if (viewer != null) { viewer.close(); viewer = null; invalidate(); } }
    public void setFrame(boolean on) { frame = on; invalidate(); }

    /** 1.0.12: lyrics for the playing track (null = none). The LYRICS button only appears when there are some. */
    private Lrc lyrics;
    /** Lyrics shown over the art; stays on from track to track until switched off (session only). */
    private boolean lyricsOn;
    public void setLyrics(Lrc l) { lyrics = l; invalidate(); }

    /** 1.0.12: pictures the playing album has (0 = unknown); more than one puts a "+N" hint on the Simple cover. */
    private int artCount;
    public void setArtCount(int n) { artCount = n; invalidate(); }

    /** The QUALITY tile's word, shared with the full-screen stats line. */
    private static String quality(Track t) {
        return t.quality != null ? t.quality
            : t.dsd ? "DSD" : t.lossy ? "Lossy" : "hires".equals(t.formatClass()) ? "Hi-res" : t.rateHz == null ? null : "CD quality";
    }

    /** Step the viewer by d pictures, skipping any that can't be decoded; restarts the auto-flip timer. */
    public void viewerStep(int d) {
        if (viewer == null) return;
        int n = viewer.size();
        if (n <= 1) return;
        for (int k = 0; k < n; k++) {
            vIdx = ((vIdx + d) % n + n) % n;
            if (!viewer.failed(vIdx)) break;
        }
        viewer.trim(vIdx);
        long now = System.currentTimeMillis();
        vNextAt = now + SLIDE_MS; vChangedAt = now;
        invalidate();
    }

    // design tokens
    private static final int BG = 0xFF0B0A0A, LIGHT = 0xFFF4F1EC, DARK_TEXT = 0xFF111111, ACCENT = 0xFFD9B48A, TAG_TEXT = 0xFFE8CBA8;
    private static final int GREEN = 0xFF5FD08B, CHIP_GREEN = 0xFF4CC27A, AMBER = 0xFFE7C15A, VIOLET = 0xFFB9A2F0,
        ORANGE = 0xFFEBB085, BLUE = 0xFFA8BEDC;
    /** Second lines on Simple (strip + tiles): a soft blue so they read apart from the white values. */
    private static final int SUB = GREEN;

    /** Nav (volume · VU · Simple/Detail) sits in the same place on both modes. */
    private static final float NAV_Y = 12;
    /** Right edge of the nav, 12 in from the right of the (possibly wider) canvas. */
    private float navRight() { return vw - 12; }
    /** Top-right controls, enlarged in 1.0.2 so they are easy to hit: height, toggle width, VU width. */
    private static final float NAV_H = 44, TOG_W = 160, VU_W = 72, EQ_W = 72, GEAR_W = 52;

    private final Typeface hanken400, hanken500, hanken600, hanken700, mono400, mono500;
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Object[]> hits = new ArrayList<>();
    private final Actions actions;

    private Poller.Snapshot snap;
    private boolean detail;
    private boolean stayOn;
    private String toast;
    private long toastUntil;
    private String[] info;           // the badge definition panel, while shown
    // 1.0.10: release notes dialog ("# " = version header, "• " = bullet), its scroll, and the optional action button
    private String notesTitle;
    private java.util.List<String> notes;
    private String notesButton;
    private Runnable notesAction;
    private String notesCancel;
    private float notesScroll, notesMax;
    private long infoUntil;
    private float s = 1, ox, oy;
    /**
     * Virtual canvas width (1.0.4). Height is always 480; width is 900 on the A6 and grows on wider screens
     * (the T10's 1600 x 600 panel gives 1280), so layouts stretch instead of leaving bands at the sides.
     */
    private float vw = 900;

    static final String[] PAGES = {"Overview", "Track & file", "Queue & playback", "Device", "Inputs, outputs & volume", "Settings & about"};
    /** 1.0.3: the last Detail page is a QR code to the feedback form (the A6 has no browser; a phone does). */
    static final int FEEDBACK_PAGE = 5;
    /** 1.0.10: the swipe carousel is pages 0-4; Settings & about (page 5) is reached only with the gear. */
    static final int CAROUSEL = 5;
    /** Where the gear came from, so a second tap goes back there. */
    private int pageBeforeSettings = 0;
    private String qrFor;
    private Bitmap qr;
    private int page = 0;
    private final float[] scroll = new float[PAGES.length];
    private float maxScroll = 0;
    private float downX, downY, downScroll;
    private boolean dragging;

    public ScreenView(Context ctx, Actions actions) {
        super(ctx);
        this.actions = actions;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // for the cover shadow blur
        hanken400 = hanken(ctx, 400);
        hanken500 = hanken(ctx, 500);
        hanken600 = hanken(ctx, 600);
        hanken700 = hanken(ctx, 700);   // 1.0.13: the Simple v4 badge
        mono400 = font(ctx, "fonts/IBMPlexMono-Regular.ttf", Typeface.MONOSPACE);
        mono500 = font(ctx, "fonts/IBMPlexMono-Medium.ttf", Typeface.MONOSPACE);
    }

    private static Typeface hanken(Context ctx, int weight) {
        try {
            return new Typeface.Builder(ctx.getAssets(), "fonts/HankenGrotesk.ttf").setFontVariationSettings("'wght' " + weight).build();
        } catch (Exception e) {
            return Typeface.create(Typeface.SANS_SERIF, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private static Typeface font(Context ctx, String path, Typeface fallback) {
        try { return Typeface.createFromAsset(ctx.getAssets(), path); } catch (Exception e) { return fallback; }
    }

    public void setSnapshot(Poller.Snapshot s) { snap = s; invalidate(); }
    public void setDetail(boolean d) { detail = d; invalidate(); }
    /** 1.0.10: jump to a Detail page (the gear opens Settings & about). */
    public void showPage(int p) {
        if (p == FEEDBACK_PAGE && page != FEEDBACK_PAGE) pageBeforeSettings = detail ? page : 0;
        page = p; scroll[p] = 0; invalidate();
    }
    public boolean onSettings() { return detail && page == FEEDBACK_PAGE; }
    /** Back from Settings to the Detail page it was opened from. */
    public void leaveSettings() { page = pageBeforeSettings; invalidate(); }
    public void setStayOn(boolean on) { stayOn = on; invalidate(); }
    /** Opens the release-notes dialog. button/action: an extra button next to Close (the Download offer), or null. */
    public void showNotes(String title, java.util.List<String> lines, String button, Runnable action) {
        showNotes(title, lines, button, action, button != null ? "Not now" : "Close");
    }

    /** Same dialog with a chosen cancel label (the reset confirmation says No / Yes, reset). */
    public void showNotes(String title, java.util.List<String> lines, String button, Runnable action, String cancel) {
        notesTitle = title; notes = lines; notesButton = button; notesAction = action; notesCancel = cancel; notesScroll = 0;
        info = null;
        invalidate();
    }

    private void closeNotes() { notes = null; notesAction = null; invalidate(); }

    public void toast(String text) { toast = text; toastUntil = System.currentTimeMillis() + 1600; invalidate(); }

    /** The update pill: null hides it; colour says the state (offer / downloading / saved / failed). */
    private String update;
    private int updateColor = GREEN;
    public void setUpdate(String text, int color) { update = text; updateColor = color; invalidate(); }
    /** 1.0.11: last update download outcome ("1.0.11 failed 1006"), carried in the feedback QR. */
    private String downloadDiag;
    public void setDownloadDiag(String d) { downloadDiag = d; }
    static final int UPDATE_OFFER = 0xFF5FD08B, UPDATE_BUSY = 0xFFD9B48A, UPDATE_FAILED = 0xFFE8806A;

    /** Pill with the label inside; tap runs actions.update(). Width is capped so it can't reach the credit. */
    private void drawUpdate(Canvas c, float x, float y, float maxW, boolean centred) {
        if (update == null) return;
        float size = 15, padX = 14;
        tp.setTypeface(hanken600);
        tp.setTextSize(size * s);
        while (tp.measureText(update) / s + 2 * padX > maxW && size > 10) { size -= 0.5f; tp.setTextSize(size * s); }
        float w = Math.min(maxW, tp.measureText(update) / s + 2 * padX);
        if (centred) x -= w / 2;
        roundRect(c, x, y, w, 30, 15, (updateColor & 0x00FFFFFF) | 0x26000000);
        tp.setColor(updateColor);
        c.drawText(TextUtils.ellipsize(update, tp, (w - 2 * padX) * s, TextUtils.TruncateAt.END).toString(), X(x + padX), Y(y + 20.5f), tp);
        hit(x, y, w, 30, actions::update);
    }

    // ================================================================ frame

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        // The A6 already excludes its sidebar from our window (measured on 0.2.0 screenshots, 2026-11-15), so the
        // design's 900 dp content area maps onto the WHOLE view; reserving another 60 dp left a blank strip.
        s = Math.min(w / 900f, h / 480f);
        vw = Math.max(900f, w / s);
        ox = (w - vw * s) / 2f;
        oy = (h - 480 * s) / 2f;
        hits.clear();
        c.drawColor(BG);
        Poller.Snapshot sn = snap;
        int tint = sn == null || sn.cover == null ? 0xFF262220 : sn.tint;
        p.reset();
        // 1.0.13 (Simple v4): glow = an ellipse 560 x 440 centred on the cover (W - 144, 192), tint -> transparent
        RadialGradient glow = new RadialGradient(X(vw - 144), Y(192), 280 * s, tint, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        Matrix gm = new Matrix();
        gm.setScale(1f, 440f / 560f, X(vw - 144), Y(192));
        glow.setLocalMatrix(gm);
        p.setShader(glow);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        if (viewer != null) {
            drawViewer(c, sn);
            long now = System.currentTimeMillis();
            if (toast != null && now < toastUntil) drawToast(c);
            postInvalidateDelayed(250);
            return;
        }

        c.save();
        c.clipRect(X(0), Y(0), X(vw), Y(480));
        if (sn == null || sn.track == null) drawWaiting(c, sn);
        else if (detail) drawDetail(c, sn);
        else drawSimple(c, sn);
        c.restore();

        long now = System.currentTimeMillis();
        if (info != null && now < infoUntil) drawInfo(c);
        if (notes != null) drawNotes(c);
        if (toast != null && now < toastUntil) drawToast(c);
        boolean animate = (sn != null && sn.track != null && sn.track.state == 3) || (toast != null && now < toastUntil)
            || (info != null && now < infoUntil);
        if (animate) postInvalidateDelayed(200);
    }

    private float X(float d) { return ox + d * s; }
    private float Y(float d) { return oy + d * s; }

    /** Loading screen: the app's signal-path logo centred, the status line under it. */
    private void drawWaiting(Canvas c, Poller.Snapshot sn) {
        float cx = vw / 2, cy = 205, k = 1.8f;     // logo = ic_launcher_foreground: 4 nodes on a line, last one green
        p.reset(); p.setAntiAlias(true); p.setColor(LIGHT); p.setStrokeWidth(3 * k * s);
        c.drawLine(X(cx - 24 * k), Y(cy), X(cx + 24 * k), Y(cy), p);
        float[] dx = {-24, -8, 8};
        for (float d : dx) c.drawCircle(X(cx + d * k), Y(cy), 5 * k * s, p);
        p.setColor(GREEN);
        c.drawCircle(X(cx + 24 * k), Y(cy), 6 * k * s, p);
        String msg = sn != null && !sn.online ? "Can't reach the player on port 9529. Retrying…" : "Loading EverSignal Player…";
        drawCentered(c, msg, 0, cy + 40, vw, 30, 18, hanken500, 0xD9FFFFFF);
        drawUpdate(c, vw / 2, cy + 90, 420, true);
        drawToggle(c, navRight() - TOG_W, NAV_Y);
    }

    /** Volume · VU · Simple/Detail, right-aligned at the same spot in both modes. Returns the left edge used. */
    private float drawNav(Canvas c, Track t) {
        float x = navRight() - TOG_W;
        drawToggle(c, x, NAV_Y);
        x -= 10 + GEAR_W;
        drawGear(c, x, NAV_Y, GEAR_W, detail && page == FEEDBACK_PAGE);
        x -= 10 + VU_W;
        drawVu(c, x, NAV_Y, VU_W);
        x -= 10 + EQ_W;
        drawEq(c, x, NAV_Y, EQ_W, t.eq);
        if (t.volume != null) {
            float volW = measure(t.volume, 20, mono500);
            float lockW = t.volumeLocked ? 18 : 0;
            x -= 12 + volW + lockW;
            text(c, t.volume, x, NAV_Y + 29, 20, mono500, 0xD9FFFFFF, volW + 2);
            if (t.volumeLocked) drawLock(c, x + volW + 6, NAV_Y + 15, 0xB3FFFFFF);
        }
        return x;
    }

    // ================================================================ Simple

    // ---------------------------------------------------------------- Simple v4 (1.0.13 design handoff, 2026-11-20)
    // Approved by Michael ("easier to read", "the most important thing pops"). Spec: design_handoff_simple_v4/README.md,
    // kept in hifi/files/eversolo-now-playing/design/. Grid units on 900 x 480; at 1280 only the left column stretches.
    // Priority order: badge, what's playing, quality, signal path, stats, transport. All positions absolute: no state
    // moves anything.

    private static final int NEUTRAL_SUB = 0x9EF4F1EC;   // LIGHT at 62%

    private void drawSimple(Canvas c, Poller.Snapshot sn) {
        Track t = sn.track;
        final float L0 = 16, LW = vw - 312;            // left column x 16 -> W - 296
        final float RX = vw - 272, RW = 256;           // right column
        boolean paused = t.badge == Track.Badge.PAUSED;
        boolean checking = t.badge == Track.Badge.CHECKING;

        // ---- right column: cover, picture count, up next, update pill, credit
        if (sn.cover != null) {
            drawCover(c, sn.cover, RX, 64, RW, 12, true);
            if (artCount > 1) drawCountChip(c, RX + RW - 10, 64 + RW - 10, "1/" + artCount);
        } else drawNoCover(c, RX, 64, RW);
        hit(RX, 64, RW, RW, actions::openViewer);
        if (t.hasQueue && sn.nextTitle != null) {
            labelV4(c, "UP NEXT", RX, 347, 0.12f);
            fitText(c, sn.nextTitle, RX, 370, 18, hanken500, LIGHT, RW);
        }
        drawUpdateV4(c, RX, 416, RW - 150);
        String credit = "made by @malbers";
        float cw = measure(credit, 13, hanken400);
        text(c, credit, vw - 16 - cw, 443, 13, hanken400, 0x80F4F1EC, cw + 2);
        hit(vw - 16 - cw - 8, 416, cw + 16, 44, actions::openChannel);

        // ---- top bar
        drawNav(c, t);
        drawPill(c, sourceChip(t).toUpperCase(Locale.US), L0, 17, 13, hanken600, 0x1AF4F1EC, LIGHT, 12, 0.12f);   // top at 20

        // ---- title / artist / album
        fitTitleV4(c, t.title == null ? "" : t.title, L0, 98, LW);
        if (t.artist != null) fitText(c, t.artist, L0, 128, 20, hanken500, 0xD9F4F1EC, LW);
        if (t.album != null) fitText(c, t.album, L0, 151, 16, hanken400, 0x94F4F1EC, LW);

        // ---- status row: badge, then the quality block
        float qx = L0 + drawBadgeV4(c, t, L0, 168, paused) + 18;
        String q = t.lossy && !t.ownPlayer && t.badgeText != null && !t.badgeText.isEmpty() ? t.badgeText + " · " + t.playerLabel : quality(t);
        String q2 = qualityLine(t);
        float qw = L0 + LW - qx;
        if (q != null) fitText(c, q, qx, q2.isEmpty() ? 199 : 189, 22, hanken600, LIGHT, qw);
        if (!q2.isEmpty()) fitText(c, q2, qx, 210, 15, mono400, 0xB8F4F1EC, qw);

        // ---- signal path, stats row, progress, transport
        drawPathV4(c, t, L0, 226, LW, 106, paused, checking);
        drawStatsV4(c, sn, L0 + 16, 344, LW - 32);
        drawProgressV4(c, sn, L0, 394, LW, paused);
        float midX = L0 + LW / 2, rowY = 410;
        drawRoundButton(c, midX - 28 - 20 - 48, rowY + 4, 48, false, "prev"); hit(midX - 28 - 20 - 48, rowY + 4, 48, 48, actions::previous);
        drawRoundButton(c, midX - 28, rowY, 56, true, t.state == 3 ? "pause" : "play"); hit(midX - 28, rowY, 56, 56, actions::playPause);
        drawRoundButton(c, midX + 28 + 20, rowY + 4, 48, false, "next"); hit(midX + 28 + 20, rowY + 4, 48, 48, actions::next);
        int tcol = 0xB8F4F1EC;
        text(c, mss(position(sn)), L0, 444, 16, mono400, paused ? 0x73F4F1EC : tcol, 80);
        String total = t.durationMs > 0 ? mss(t.durationMs) : "";
        float tw = measure(total, 16, mono400);
        text(c, total, L0 + LW - tw, 444, 16, mono400, tcol, tw + 2);
    }

    /** "44.1 kHz · 16-bit · 2 ch" / "2.82 MHz · 1-bit · 5 ch"; empty when the player reports no rate. */
    private static String qualityLine(Track t) {
        if (t.rateHz == null) return "";
        String dc = Track.depthChannels(t.bits, t.channels);
        return Track.rateText(t.rateHz, t.dsd) + (dc.isEmpty() ? "" : " · " + dc);
    }

    /** Title: Hanken 600, 36 shrinking to 28 to stay on one line, then an ellipsis. */
    private void fitTitleV4(Canvas c, String title, float x, float baseline, float w) {
        tp.setTypeface(hanken600); tp.setColor(LIGHT); tp.setLetterSpacing(-0.01f);
        float sz = 36;
        tp.setTextSize(sz * s);
        while (tp.measureText(title) > w * s && sz > 28) { sz -= 1; tp.setTextSize(sz * s); }
        c.drawText(TextUtils.ellipsize(title, tp, w * s, TextUtils.TruncateAt.END).toString(), X(x), Y(baseline), tp);
        tp.setLetterSpacing(0);
    }

    private void labelV4(Canvas c, String text, float x, float baseline, float spacing) {
        tp.setTypeface(hanken600); tp.setTextSize(13 * s); tp.setColor(ACCENT); tp.setLetterSpacing(spacing);
        c.drawText(text, X(x), Y(baseline), tp);
        tp.setLetterSpacing(0);
    }

    /** v4 badge colours: green clean (incl. DSD native), amber changed, violet MQA, blue Checking, neutral Paused. */
    private int badgeColorV4(Track.Badge b) {
        switch (b) {
            case BIT_PERFECT: case DSD_NATIVE: return GREEN;
            case EQ_ACTIVE: case RESAMPLED: case DSD_TO_PCM: case VOLUME: return AMBER;
            case MQA: return VIOLET;
            case CHECKING: case HIRES_STREAM: return BLUE;
            case PAUSED: return LIGHT;
            default: return ORANGE;   // Lossy
        }
    }

    /** The status-row badge (48 tall, tappable). Returns its width. Paused adds the hint in smaller type. */
    private float drawBadgeV4(Canvas c, Track t, float x, float y, boolean paused) {
        if (t.badge == Track.Badge.NONE && !paused) return -18;
        String txt = paused ? "Paused" : t.badgeText;
        int col = paused ? LIGHT : badgeColorV4(t.badge);
        String hint = paused ? "· press play for the signal path" : null;
        float tw = measure(txt, 22, hanken700), hw = hint == null ? 0 : 10 + measure(hint, 14, hanken500);
        boolean dot = !paused;
        float bw = 14 + (dot ? 10 + 10 : 0) + tw + hw + 18;
        roundRect(c, x, y, bw, 48, 24, (col & 0x00FFFFFF) | (paused ? 0x1A000000 : col == BLUE ? 0x24000000 : 0x26000000));
        float tx = x + 14;
        if (dot) {
            p.reset(); p.setAntiAlias(true);
            p.setColor(t.badge == Track.Badge.CHECKING ? pulse(col) : col);
            c.drawCircle(X(tx + 5), Y(y + 24), 5 * s, p);
            tx += 20;
        }
        text(c, txt, tx, y + 32, 22, hanken700, col, tw + 4);
        if (hint != null) text(c, hint, tx + tw + 10, y + 30, 14, hanken500, 0xB3F4F1EC, hw);
        if (!paused && t.badge != Track.Badge.CHECKING) hit(x, y, bw, 48, () -> showInfo(t));
        return bw;
    }

    /**
     * Signal path: four equal nodes. Sub-lines green while the path is clean; the node that changes the signal turns
     * amber (value and sub-lines), every node after it goes neutral. Checking: Output and Port at 40%. Paused: Output
     * and Port show "…" at 45% with no sub-lines (Source and Processing describe the file and stay).
     */
    private void drawPathV4(Canvas c, Track t, float x, float y, float w, float h, boolean paused, boolean checking) {
        roundRect(c, x, y, w, h, 12, 0x4D000000);
        strokeRect(c, x + 0.5f, y + 0.5f, w - 1, h - 1, 12, 0x14F4F1EC, 1);
        String[][] n = nodesV4(t);
        int changed = t.badge == Track.Badge.EQ_ACTIVE ? 1
            : t.badge == Track.Badge.RESAMPLED || t.badge == Track.Badge.DSD_TO_PCM ? 2 : -1;
        float gut = 24, nodeW = (w - 32 - 3 * gut) / 4f, nx = x + 16;
        for (int i = 0; i < 4; i++) {
            boolean pending = (paused || checking) && i >= 2;
            int valCol = i == changed ? AMBER : LIGHT;
            int subCol = i == changed ? AMBER : (changed >= 0 && i > changed) || checking ? NEUTRAL_SUB : GREEN;
            int alpha = paused && i >= 2 ? 0x73 : checking && i >= 2 ? 0x66 : 0xFF;
            labelV4(c, n[i][0], nx, y + 12 + 13, 0.1f);
            String val = paused && i >= 2 ? "…" : n[i][1];
            fitText(c, val, nx, y + 51, 20, mono500, withAlpha(valCol, alpha), nodeW);
            if (!(paused && i >= 2)) {
                if (!n[i][2].isEmpty()) fitText(c, n[i][2], nx, y + 72, 14, mono400, withAlpha(subCol, alpha), nodeW);
                if (!n[i][3].isEmpty()) fitText(c, n[i][3], nx, y + 92, 14, mono400, withAlpha(subCol, alpha), nodeW);
            }
            nx += nodeW;
            if (i < 3) { drawArrow(c, nx + 5, y + 24 + 12, 0x52F4F1EC); nx += gut; }
        }
    }

    private static int withAlpha(int col, int alpha) {
        int a = ((col >>> 24) * alpha) / 255;
        return (col & 0x00FFFFFF) | (a << 24);
    }

    private String[][] nodesV4(Track t) {
        String src, s1, s2;
        if (t.dsd) {
            src = t.container == null ? "DSD" : t.container;
            s1 = Track.dsdName(t.rateHz);
            s2 = Track.depthChannels(1.0, t.channels);
        } else {
            src = t.container != null ? t.container : t.codec != null ? t.codec : sourceChip(t);
            s1 = t.rateHz == null ? "" : Track.rateText(t.rateHz, false);
            s2 = t.bits == null ? (t.lossy && t.bitrate != null ? t.bitrate : "") : Math.round(t.bits) + "-bit";
        }
        String out, o1;
        if (t.outCodec == null) { out = "…"; o1 = ""; }
        else if (Track.isDsdCodec(t.outCodec)) { out = t.outCodec.replace(" ", ""); o1 = Track.rateText(t.outRateHz, true); }
        else { out = t.outCodec; o1 = t.outRateHz == null ? "" : Track.rateText(t.outRateHz, false); }
        return new String[][]{
            {"SOURCE", src, s1, s2},
            {"PROCESSING", t.eq ? (t.eqApplied() ? "EQ on" : "EQ skipped") : "EQ off", t.dsp ? "DSP on" : "DSP off", t.sub ? "Sub on" : "Sub off"},
            {"OUTPUT", out, o1, ""},
            {"PORT", t.iface == null ? "…" : t.iface, t.iface != null && t.analogPort() ? "analog out" : "", ""},
        };
    }

    /** BITRATE (file average) · FILE SIZE · INPUT, left-aligned; any the player doesn't report is left out. */
    private void drawStatsV4(Canvas c, Poller.Snapshot sn, float x, float y, float w) {
        Track t = sn.track;
        Double bps = sn.exactBps != null ? sn.exactBps : t.bitrateBps;
        String size = sn.sizeBytes != null ? sn.sizeBytes : t.fileSize;
        Poller.Device d = sn.device;
        String in = d.inputIndex >= 0 && d.inputIndex < d.inputs.size() ? d.inputs.get(d.inputIndex) : null;
        float ix = x, end = x + w;
        if (bps != null) {
            String v = Track.bpsText(bps);
            float vw1 = measure(v, 15, mono500), aw = measure("file average", 13, hanken400);
            labelV4(c, "BITRATE", ix, y + 13, 0.1f);
            text(c, v, ix, y + 32, 15, mono500, LIGHT, vw1 + 2);
            text(c, "file average", ix + vw1 + 6, y + 32, 13, hanken400, 0x80F4F1EC, aw + 2);
            ix += Math.max(vw1 + 6 + aw, measure("BITRATE", 13, hanken600)) + 28;
        }
        if (size != null && ix < end) {
            labelV4(c, "FILE SIZE", ix, y + 13, 0.1f);
            text(c, size, ix, y + 32, 15, mono500, LIGHT, end - ix);
            ix += Math.max(measure(size, 15, mono500), measure("FILE SIZE", 13, hanken600) * 1.12f) + 28;
        }
        if (in != null && ix < end) {
            labelV4(c, "INPUT", ix, y + 13, 0.1f);
            fitText(c, in, ix, y + 32, 15, mono500, LIGHT, end - ix);
        }
    }

    private void drawProgressV4(Canvas c, Poller.Snapshot sn, float x, float y, float w, boolean paused) {
        Track t = sn.track;
        float frac = t.durationMs > 0 ? Math.min(1f, position(sn) / (float) t.durationMs) : 0;
        roundRect(c, x, y, w, 4, 2, 0x24F4F1EC);
        if (frac > 0) roundRect(c, x, y, w * frac, 4, 2, paused ? 0x73F4F1EC : LIGHT);
    }

    /** "1/7" chip, bottom-right of the cover: stacked-pictures icon + count. (rx, by) = the chip's right and bottom. */
    private void drawCountChip(Canvas c, float rx, float by, String txt) {
        float tw = measure(txt, 13, mono500), w = 8 + 13 + 6 + tw + 10, x = rx - w, y = by - 26;
        roundRect(c, x, y, w, 26, 13, 0xC70B0A0A);
        float ix = x + 8, iy = y + 6.5f;
        strokeRect(c, ix + 3, iy, 10, 10, 2, 0xB3F4F1EC, 1.3f);
        strokeRect(c, ix, iy + 3, 10, 10, 2, LIGHT, 1.3f);
        text(c, txt, ix + 13 + 6, y + 18, 13, mono500, LIGHT, tw + 2);
    }

    /** No cover art: dark tile, record icon, "No cover art". */
    private void drawNoCover(Canvas c, float x, float y, float sz) {
        roundRect(c, x, y, sz, sz, 12, 0xFF1C1916);
        strokeRect(c, x + 0.5f, y + 0.5f, sz - 1, sz - 1, 12, 0x14F4F1EC, 1);
        float cx = x + sz / 2, cy = y + sz / 2 - 12;
        p.reset(); p.setAntiAlias(true); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * s); p.setColor(0x47F4F1EC);
        c.drawCircle(X(cx), Y(cy), 33 * s, p);
        c.drawCircle(X(cx), Y(cy), 9 * s, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(X(cx), Y(cy), 2 * s, p);
        drawCentered(c, "No cover art", x, cy + 46, sz, 20, 14, hanken500, 0x73F4F1EC);
    }

    /** Update pill (only when an update exists): 44 tall, tan outline + tint, up arrow, the offer text. */
    private void drawUpdateV4(Canvas c, float x, float y, float maxW) {
        if (update == null) return;
        int col = updateColor == GREEN ? ACCENT : updateColor;
        float tw = Math.min(measure(update, 14, hanken600), maxW - 52), w = 16 + 16 + 8 + tw + 18;
        roundRect(c, x, y, w, 44, 22, (col & 0x00FFFFFF) | 0x1F000000);
        strokeRect(c, x + 0.75f, y + 0.75f, w - 1.5f, 44 - 1.5f, 22, col, 1.5f);
        p.reset(); p.setAntiAlias(true); p.setColor(col); p.setStrokeWidth(1.8f * s); p.setStrokeCap(Paint.Cap.ROUND);
        float ax = x + 16 + 8, ay = y + 22;
        c.drawLine(X(ax), Y(ay + 6), X(ax), Y(ay - 6), p);
        c.drawLine(X(ax), Y(ay - 6), X(ax - 5), Y(ay - 1), p);
        c.drawLine(X(ax), Y(ay - 6), X(ax + 5), Y(ay - 1), p);
        tp.setTypeface(hanken600); tp.setTextSize(14 * s); tp.setColor(col);
        c.drawText(TextUtils.ellipsize(update, tp, tw * s, TextUtils.TruncateAt.END).toString(), X(x + 40), Y(y + 27), tp);
        hit(x, y, w, 44, actions::update);
    }

    /** Pre-1.0.13 Simple screen, kept for reference while v4 is device-tested; not drawn. */
    @SuppressWarnings("unused")
    private void drawSimpleV3(Canvas c, Poller.Snapshot sn) {
        Track t = sn.track;
        // 1.0.2: the stats are the point, so the cover shrinks (340 -> 260) and the left side grows; no text below ~16
        final float left = 32, right = vw - 302, w = right - left;
        final float coverX = vw - 272, coverY = 92, coverS = 260;

        drawCover(c, sn.cover, coverX, coverY, coverS, 10, true);
        hit(coverX, coverY, coverS, coverS, actions::openViewer);   // 1.0.12: full-screen album art
        // "1/3": more pictures behind a tap (Michael's pick over "+2"), whenever there is more than one (Michael 2026-11-20)
        if (artCount > 1 && sn.cover != null) {
            String more = "1/" + artCount;
            float mw = measure(more, 15, mono500) + 18;
            roundRect(c, coverX + coverS - mw - 10, coverY + coverS - 36, mw, 26, 13, 0xB3000000);
            text(c, more, coverX + coverS - mw - 1, coverY + coverS - 18, 15, mono500, LIGHT, mw);
        }
        // 1.0.9: Up next moved under the cover (it sat left of the play controls and made the centred row look
        // lopsided, 2026-11-17). Cover column top to bottom: cover (to 352), Up next, update pill, credit.
        if (t.hasQueue && sn.nextTitle != null) {
            label(c, "UP NEXT", coverX, 378, 13);
            fitText(c, sn.nextTitle, coverX, 401, 19, hanken500, 0xE0FFFFFF, coverS);
        }
        // update pill above the credit, as wide as the cover
        drawUpdate(c, coverX, 412, coverS, false);
        // credit under the cover, right-aligned with it; tap opens the YouTube channel
        String credit = "made by @malbers";
        float cw = measure(credit, 15, hanken500);
        text(c, credit, vw - 12 - cw, 467, 15, hanken500, 0x8CFFFFFF, cw + 2);
        hit(vw - 12 - cw - 8, 448, cw + 16, 28, actions::openChannel);
        drawNav(c, t);
        String chip = sourceChip(t);
        drawPill(c, chip.toUpperCase(Locale.US), left, NAV_Y, 14, hanken600, 0x1AFFFFFF, Color.WHITE, 12, 0.12f);

        // transport (bottom)
        float rowY = 416;   // 1.0.9: rows spaced out (strip 150, tiles 262, badge 334, progress 378, buttons 416)
        float progY = 378;
        drawProgress(c, sn, left, progY, w, 17);
        // 1.0.9: the three buttons are centred on the WHOLE screen (an owner saw them off-centre, 2026-11-17). 1.0.8
        // centred them under the progress bar, which only spans the left column, so they still read left of centre.
        // The row holds only the buttons now (Up next moved under the cover), so nothing else competes for it.
        final float groupW = 48 + 8 + 58 + 8 + 48;
        float bx = Math.min(vw / 2 - groupW / 2, right - groupW);   // never under the cover column
        drawRoundButton(c, bx, rowY + 6, 48, false, "prev"); hit(bx, rowY + 6, 48, 48, actions::previous);
        bx += 48 + 8;
        drawRoundButton(c, bx, rowY + 1, 58, true, t.state == 3 ? "pause" : "play"); hit(bx, rowY + 1, 58, 58, actions::playPause);
        bx += 58 + 8;
        drawRoundButton(c, bx, rowY + 6, 48, false, "next"); hit(bx, rowY + 6, 48, 48, actions::next);

        // badge (+ MQA if not already the badge) + note + format
        float badgeY = 334;
        if (t.badge != Track.Badge.NONE) {
            boolean checking = t.badge == Track.Badge.CHECKING;
            float bw = drawBadge(c, t.badgeText, badgeColor(t.badge), left, badgeY, 18, checking);
            float nx = left + bw + 10;
            if (!checking) hit(left, badgeY, bw, 31, () -> showInfo(t));
            if (t.mqaOut && t.badge != Track.Badge.MQA) nx += drawBadge(c, "MQA", VIOLET, nx, badgeY, 18) + 10;
            String note = t.badgeNote == null ? "" : t.badgeNote;
            if (t.formatShort != null) note = note.isEmpty() ? t.formatShort : note + " · " + t.formatShort;
            // shrink before cutting: the format at the end must stay readable
            fitText(c, note, nx + 2, badgeY + 22, 17, mono400, 0xBFFFFFFF, right - nx - 2);
        }

        // stat tiles
        drawTiles(c, sn, left, 262, w, 60);
        // signal strip
        drawStrip(c, t, left, 150, w, 100);

        // titles: one line, shrinking to fit before any ellipsis, so nothing can run into the strip
        fitTitle(c, t.title == null ? "" : t.title, left, 84, w);
        if (t.artist != null) fitText(c, t.artist, left, 113, 22, hanken400, 0xD9FFFFFF, w);
        if (t.album != null) fitText(c, t.album, left, 139, 20, hanken400, 0xA6FFFFFF, w);
    }

    /** The top-left chip always names the source: TIDAL, Apple Music, ... or Library for local files. */
    private String sourceChip(Track t) {
        if (!t.ownPlayer) return t.playerLabel != null ? t.playerLabel : "External source";
        if (t.service != null) return Track.pretty(t.service);
        return "Library";
    }

    private void fitTitle(Canvas c, String title, float x, float baseline, float w) {
        tp.setTypeface(hanken600);
        tp.setColor(Color.WHITE);
        tp.setLetterSpacing(-0.015f);
        float sz = 38;
        tp.setTextSize(sz * s);
        while (tp.measureText(title) > w * s && sz > 30) { sz -= 1; tp.setTextSize(sz * s); }
        c.drawText(TextUtils.ellipsize(title, tp, w * s, TextUtils.TruncateAt.END).toString(), X(x), Y(baseline), tp);
        tp.setLetterSpacing(0);
    }

    private void drawStrip(Canvas c, Track t, float x, float y, float w, float h) {
        roundRect(c, x, y, w, h, 12, 0x52000000);
        strokeRect(c, x + 0.5f, y + 0.5f, w - 1, h - 1, 12, 0x14FFFFFF, 1);
        float pad = 14, arrowW = 26;
        float nodeW = (w - 2 * pad - 3 * arrowW) / 4f;
        String[][] nodes = nodes(t);
        float nx = x + pad;
        for (int i = 0; i < 4; i++) {
            label(c, nodes[i][0], nx, y + 21, 13);
            fitText(c, nodes[i][1], nx, y + 48, 24, mono500, Color.WHITE, nodeW);
            fitText(c, nodes[i][2], nx, y + 70, 17, mono400, SUB, nodeW);
            fitText(c, nodes[i][3], nx, y + 90, 17, mono400, SUB, nodeW);
            nx += nodeW;
            if (i < 3) { drawArrow(c, nx + 6, y + 35, 0x52FFFFFF); nx += arrowW; }
        }
    }

    private String[][] nodes(Track t) {
        String src, s1, s2;
        if (t.dsd) { src = t.container == null ? "DSD" : t.container; s1 = Track.dsdName(t.rateHz); s2 = "1-bit"; }
        else {
            // never N/A on Simple: no format reported -> the source's own name, e.g. "Apple Music"
            src = t.container != null ? t.container : t.codec != null ? t.codec : sourceChip(t);
            s1 = t.rateHz == null ? "" : Track.rateText(t.rateHz, false);
            s2 = t.bits == null ? (t.lossy && t.bitrate != null ? t.bitrate : "") : Math.round(t.bits) + "-bit";
        }
        String out, o1;
        if (t.outCodec == null) { out = "…"; o1 = ""; }
        else if (Track.isDsdCodec(t.outCodec)) { out = t.outCodec.replace(" ", ""); o1 = Track.rateText(t.outRateHz, true); }
        else { out = t.outCodec; o1 = t.outRateHz == null ? "" : Track.rateText(t.outRateHz, false); }
        return new String[][]{
            {"SOURCE", src, s1, s2},
            {"PROCESSING", t.eq ? (t.eqApplied() ? "EQ on" : "EQ skipped") : "EQ off", t.dsp ? "DSP on" : "DSP off", t.sub ? "Sub on" : "Sub off"},
            {"OUTPUT", out, o1, ""},
            {"PORT", t.iface == null ? "…" : t.iface, t.iface == null ? "" : (t.analogPort() ? "analog out" : "digital out"), ""},
        };
    }

    /** Quality · Rate/depth · Bitrate (exact) · File size · In → Out. A tile the source doesn't report is left out. */
    private void drawTiles(Canvas c, Poller.Snapshot sn, float x, float y, float w, float h) {
        Track t = sn.track;
        List<String[]> tiles = new ArrayList<>();
        String quality = quality(t);
        // no source line: the top-left chip already names it
        if (quality != null) tiles.add(new String[]{"QUALITY", quality, ""});
        // 1.0.12: channels on the second line, always (2 ch too, as the stock player shows it). Pierre C. 2026-11-20:
        // a 5-channel DSF and a 5.1 ALAC showed no channel count on Simple.
        if (t.rateHz != null) tiles.add(new String[]{"RATE / DEPTH", Track.rateText(t.rateHz, t.dsd), Track.depthChannels(t.bits, t.channels)});
        // 1.0.8: same units as the file-average line ("1.75 Mbps", not "1,748,591 bps"), seen side by side 2026-11-17
        if (sn.exactBps != null) tiles.add(new String[]{"BITRATE", Track.bpsText(sn.exactBps), "file average"});
        else if (t.bitrateBps != null) tiles.add(new String[]{"BITRATE", Track.bpsText(t.bitrateBps), "file average"});
        String size = sn.sizeBytes != null ? sn.sizeBytes : t.fileSize;
        if (size != null) tiles.add(new String[]{"FILE SIZE", size, ""});
        Poller.Device d = sn.device;
        String outName = d.outputIndex >= 0 && d.outputIndex < d.outputs.size() ? d.outputs.get(d.outputIndex) : t.iface;
        String inName = d.inputIndex >= 0 && d.inputIndex < d.inputs.size() ? d.inputs.get(d.inputIndex) : null;
        if (outName != null) tiles.add(new String[]{"IN → OUT", outName, inName == null ? "" : inName});
        if (tiles.isEmpty()) return;
        float gap = 6, tw = (w - gap * (tiles.size() - 1)) / tiles.size();
        float tx = x;
        for (String[] tile : tiles) {
            roundRect(c, tx, y, tw, h, 9, 0x12FFFFFF);
            fitLabel(c, tile[0], tx + 9, y + 17, 13, tw - 18);
            fitText(c, tile[1], tx + 9, y + 39, 20, mono500, Color.WHITE, tw - 18);
            fitText(c, tile[2], tx + 9, y + 54, 15, mono400, SUB, tw - 18);
            tx += tw + gap;
        }
    }

    private int badgeColor(Track.Badge b) {
        switch (b) {
            case DSD_NATIVE: case DSD_TO_PCM: return AMBER;
            case MQA: return VIOLET;
            case BIT_PERFECT: return GREEN;
            case HIRES_STREAM: return BLUE;
            case PAUSED: case CHECKING: return 0xFFA9A39B;
            default: return ORANGE;
        }
    }

    /** Draws one badge pill; returns its width. */
    private float drawBadge(Canvas c, String text, int col, float x, float y, float size) {
        return drawBadge(c, text, col, x, y, size, false);
    }

    /** 1.0.8: alpha of the "Checking…" dot, breathing once a second; keeps the view redrawing while it shows. */
    private int pulse(int col) {
        double ph = (System.currentTimeMillis() % 1200) / 1200.0;
        int a = (int) (90 + 165 * (0.5 + 0.5 * Math.cos(ph * 2 * Math.PI)));
        postInvalidateDelayed(50);
        return (col & 0x00FFFFFF) | (a << 24);
    }

    private float drawBadge(Canvas c, String text, int col, float x, float y, float size, boolean pulseDot) {
        tp.setTypeface(hanken600);
        tp.setTextSize(size * s);
        float tw = tp.measureText(text) / s;
        float bw = 10 + 7 + 7 + tw + 12, bh = size + 13;
        roundRect(c, x, y, bw, bh, bh / 2, (col & 0x00FFFFFF) | 0x26000000);
        p.reset(); p.setAntiAlias(true); p.setColor(pulseDot ? pulse(col) : col);
        c.drawCircle(X(x + 10 + 3.5f), Y(y + bh / 2), 3.5f * s, p);
        tp.setColor(col);
        c.drawText(text, X(x + 10 + 7 + 7), Y(y + bh / 2 + size * 0.35f), tp);
        return bw;
    }

    private void showInfo(Track t) {
        // 1.0.5: explains the badge that was tapped, with a live "to get Bit-perfect" checklist (BadgeInfo)
        // 1.0.13: Simple v4 dropped the on-screen "44.1kHz in = 44.1kHz out" line; it now leads the explanation
        java.util.List<String> lines = new java.util.ArrayList<>(BadgeInfo.lines(t));
        if (t.badgeNote != null && !t.badgeNote.isEmpty() && !lines.isEmpty() && !lines.contains(t.badgeNote)) lines.add(1, t.badgeNote);
        info = lines.toArray(new String[0]);
        infoUntil = System.currentTimeMillis() + 15000;
        invalidate();
    }

    /** Passthrough warning lines in the badge panel. */
    private static final int WARN_RED = 0xFFFF6B5B;

    private void drawInfo(Canvas c) {
        float w = 820, lh = 28, maxW = w - 48;
        // 1.0.6: long lines word-wrap (continuations keep the line's indent) instead of shrinking to fit
        java.util.List<Object[]> rows = new java.util.ArrayList<>();   // {text, size, typeface, colour}
        for (int i = 0; i < info.length; i++) {
            boolean head = i == 0, last = i == info.length - 1, warn = BadgeInfo.WARN.equals(info[i]);
            float size = head ? 19 : 17;
            Typeface tf = head || warn ? hanken600 : hanken400;
            int col = warn ? WARN_RED : last ? 0x99FFFFFF : head ? Color.WHITE : 0xD9FFFFFF;
            for (String r : wrap(info[i], size, tf, maxW)) rows.add(new Object[]{r, size, tf, col});
        }
        float h = 30 + rows.size() * lh, x = (vw - w) / 2f, y = Math.max(8, (480 - h) / 2f);
        p.reset(); p.setColor(0xB3000000);
        c.drawRect(X(0), Y(0), X(vw), Y(480), p);
        roundRect(c, x, y, w, h, 14, 0xFF1B1A19);
        strokeRect(c, x + 0.5f, y + 0.5f, w - 1, h - 1, 14, 0x24FFFFFF, 1);
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            fitText(c, (String) r[0], x + 24, y + 32 + i * lh, (Float) r[1], (Typeface) r[2], (Integer) r[3], maxW);
        }
        hits.add(new Object[]{new RectF(0, 0, getWidth(), getHeight()), (Runnable) () -> { info = null; invalidate(); }});
    }

    /** A tappable text link (accent colour, underlined). Returns its width. */
    private float link(Canvas c, String t, float x, float baseline, float size, Runnable r) {
        float w = measure(t, size, hanken600);
        text(c, t, x, baseline, size, hanken600, ACCENT, w + 4);
        roundRect(c, x, baseline + 4, w, 1.5f, 0.75f, ACCENT);
        hit(x - 6, baseline - size - 8, w + 12, size + 20, r);
        return w;
    }

    /** Reset (design 1.0.10): first tap arms "Tap to confirm" for 3 s, second tap clears and shows "Cleared" 1.5 s. */
    private long resetArmedUntil, resetDoneUntil;
    private void resetTapped() {
        long now = System.currentTimeMillis();
        if (now < resetArmedUntil) { resetArmedUntil = 0; resetDoneUntil = now + 1500; actions.resetStats(); }
        else { resetArmedUntil = now + 3000; resetDoneUntil = 0; }
        invalidate();
    }

    /** Check for updates button label: "Checking…", then the result for a moment. null = the normal label. */
    private String checkLabel;
    private long checkLabelUntil;
    public void checkStatus(String label, long ms) { checkLabel = label; checkLabelUntil = System.currentTimeMillis() + ms; invalidate(); }
    /** The link on the Feedback page: every bundled release, newest first. */
    private void openAllNotes() { showNotes("What's new in EverSignal", ReleaseNotes.all(), null, null); }

    /** 1.0.10: release notes dialog. Scrolls (drag or wheel); tap outside or Close to dismiss. */
    private void drawNotes(Canvas c) {
        float w = Math.min(820, vw - 40), x = (vw - w) / 2f;
        float padX = 28, maxW = w - 2 * padX - 10;
        // lay out: {text, size, typeface, colour, gapBefore}
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        for (String line : notes) {
            if (line.startsWith("# ")) {
                rows.add(new Object[]{line.substring(2), 17f, hanken600, ACCENT, rows.isEmpty() ? 0f : 14f});
            } else {
                boolean bullet = line.startsWith("\u2022 ");
                String body = bullet ? line.substring(2) : line;
                java.util.List<String> parts = wrap(body, 16, hanken400, maxW - (bullet ? 22 : 0));
                for (int i = 0; i < parts.size(); i++) {
                    rows.add(new Object[]{parts.get(i).trim(), 16f, hanken400, 0xD9FFFFFF, 0f, bullet, i == 0});
                }
            }
        }
        float lh = 25, total = 0;
        for (Object[] r : rows) total += lh + (Float) r[4];
        // fits the content (a short confirmation is small), up to 420 tall; longer content scrolls
        float h = Math.min(420, 62 + total + 12 + 64), y = Math.max(30, (480 - h) / 2f);
        float bodyTop = y + 62, bodyBottom = y + h - 64;
        p.reset(); p.setColor(0xB3000000);
        c.drawRect(X(0), Y(0), X(vw), Y(480), p);
        // order matters: hits are searched last-first, so the buttons win, then the panel, then "outside"
        hits.add(new Object[]{new RectF(0, 0, getWidth(), getHeight()), (Runnable) this::closeNotes});
        hits.add(new Object[]{new RectF(X(x), Y(y), X(x + w), Y(y + h)), (Runnable) () -> { }});
        roundRect(c, x, y, w, h, 14, 0xFF1B1A19);
        strokeRect(c, x + 0.5f, y + 0.5f, w - 1, h - 1, 14, 0x24FFFFFF, 1);
        fitText(c, notesTitle, x + padX, y + 40, 22, hanken600, Color.WHITE, maxW);

        notesMax = Math.max(0, total - (bodyBottom - bodyTop));
        notesScroll = Math.max(0, Math.min(notesMax, notesScroll));

        c.save();
        c.clipRect(X(x), Y(bodyTop), X(x + w), Y(bodyBottom));
        float yy = bodyTop - notesScroll;
        for (Object[] r : rows) {
            yy += (Float) r[4] + lh;
            if (yy < bodyTop - lh || yy > bodyBottom + lh) continue;
            boolean bullet = r.length > 5 && (Boolean) r[5];
            float tx = x + padX + (bullet ? 22 : 0);
            if (bullet && (Boolean) r[6]) text(c, "•", x + padX + 4, yy - 7, 16, hanken600, ACCENT, 20);
            text(c, (String) r[0], tx, yy - 7, (Float) r[1], (Typeface) r[2], (Integer) r[3], maxW + 10);
        }
        c.restore();
        if (notesMax > 0) {
            float track = bodyBottom - bodyTop, barH = Math.max(24, track * track / total);
            roundRect(c, x + w - 12, bodyTop + (track - barH) * (notesScroll / notesMax), 3, barH, 1.5f, 0x40FFFFFF);
        }

        // buttons, right-aligned: [Not now / Close] [Download ...]
        float bh = 40, by = y + h - bh - 14, bx = x + w - padX;
        if (notesButton != null) {
            float bw = measure(notesButton, 17, hanken600) + 36;
            bx -= bw;
            roundRect(c, bx, by, bw, bh, bh / 2, GREEN);
            drawCentered(c, notesButton, bx, by, bw, bh, 17, hanken600, DARK_TEXT);
            Runnable a = notesAction;
            hit(bx, by, bw, bh, () -> { closeNotes(); if (a != null) a.run(); });
            bx -= 12;
        }
        String close = notesCancel != null ? notesCancel : "Close";
        float cw = measure(close, 17, hanken600) + 36;
        bx -= cw;
        roundRect(c, bx, by, cw, bh, bh / 2, 0x1AFFFFFF);
        drawCentered(c, close, bx, by, cw, bh, 17, hanken600, Color.WHITE);
        hit(bx, by, cw, bh, this::closeNotes);
        if (notesMax > 0) text(c, "Scroll for more", x + padX, by + 26, 14, hanken400, 0x80FFFFFF, 200);
    }

    private void drawProgress(Canvas c, Poller.Snapshot sn, float x, float y, float w, float size) {
        Track t = sn.track;
        long pos = position(sn);
        float frac = t.durationMs > 0 ? Math.min(1f, pos / (float) t.durationMs) : 0;
        roundRect(c, x, y, w, 4, 2, 0x24FFFFFF);
        if (frac > 0) roundRect(c, x, y, w * frac, 4, 2, LIGHT);
        text(c, mss(pos), x, y + 4 + 6 + size, size, mono400, 0x9EFFFFFF, 90);
        String total = t.durationMs > 0 ? mss(t.durationMs) : "";
        float tw = measure(total, size, mono400);
        text(c, total, x + w - tw, y + 4 + 6 + size, size, mono400, 0x9EFFFFFF, tw + 2);
    }

    private static long position(Poller.Snapshot sn) {
        Track t = sn.track;
        long pos = t.positionMs;
        if (t.state == 3) pos += System.currentTimeMillis() - sn.stateAt;
        return t.durationMs > 0 ? Math.min(pos, t.durationMs) : pos;
    }

    private static String mss(long ms) {
        long sec = ms / 1000;
        return (sec / 60) + ":" + String.format(Locale.US, "%02d", sec % 60);
    }

    // ================================================================ Detail header + page 1 (Overview, as approved)

    private void drawDetail(Canvas c, Poller.Snapshot sn) {
        Track t = sn.track;
        p.reset(); p.setColor(0x8C080808);
        c.drawRect(X(0), Y(0), X(vw), Y(480), p);

        float navLeft = drawNav(c, t);
        float px = navLeft - 12 - NAV_H;
        drawRoundButton(c, px, NAV_Y, NAV_H, true, t.state == 3 ? "pause" : "play");
        hit(px, NAV_Y, NAV_H, NAV_H, actions::playPause);

        float hx = 16, hy = 10;
        drawCover(c, sn.cover, hx, hy + 1, 50, 6, false);
        hit(hx, hy + 1, 50, 50, actions::openViewer);
        fitText(c, t.title == null ? "" : t.title, hx + 62, hy + 21, 20, hanken600, Color.WHITE, px - hx - 62 - 12);
        float by = hy + 31, bx = hx + 62;
        if (t.badge != Track.Badge.NONE) {
            tp.setTypeface(hanken600); tp.setTextSize(14 * s);
            float tw = tp.measureText(t.badgeText) / s;
            int col = badgeColor(t.badge);
            float bw = 7 + 6 + 6 + tw + 9;
            roundRect(c, bx, by, bw, 21, 10.5f, (col & 0x00FFFFFF) | 0x26000000);
            boolean checking = t.badge == Track.Badge.CHECKING;
            p.reset(); p.setAntiAlias(true); p.setColor(checking ? pulse(col) : col);
            c.drawCircle(X(bx + 7 + 3), Y(by + 10.5f), 3 * s, p);
            tp.setColor(col);
            c.drawText(t.badgeText, X(bx + 19), Y(by + 15.5f), tp);
            if (!checking) hit(bx, by, bw, 21, () -> showInfo(t));
            bx += bw + 10;
        }
        long pos = position(sn);
        float frac = t.durationMs > 0 ? Math.min(1f, pos / (float) t.durationMs) : 0;
        // 1.0.10: the bar gives way first, so the time never runs under the pause button
        String time = mss(pos) + " / " + (t.durationMs > 0 ? mss(t.durationMs) : "–");
        float timeW = measure(time, 14, mono400), room = px - 10 - bx;
        float barW = Math.min(200, room - timeW - 18);
        if (barW >= 30) {
            roundRect(c, bx, by + 9, barW, 3, 1.5f, 0x24FFFFFF);
            if (frac > 0) roundRect(c, bx, by + 9, barW * frac, 3, 1.5f, LIGHT);
            bx += barW + 10;
        }
        text(c, time, bx, by + 15.5f, 14, mono400, 0xB3FFFFFF, Math.max(0, px - 4 - bx));

        drawDots(c);
        if (page == FEEDBACK_PAGE) { drawFeedback(c, sn); return; }
        if (page > 0) { drawStatsPage(c, sn); return; }

        // Two columns, zoomed, scrolling. What you check at the rack comes first: Music + Source | Signal path;
        // then Session + Link | Device further down.
        beginContent(c);
        float musicH = 27 + 48 + (t.tags.isEmpty() ? 0 : 19) + 9 * 16.5f + 10, sourceH = 118, sessH = 268, linkH = 96, signalH = 388;
        float gap = 8;
        if (columns() == 3) {
            // wide screens (T10): Music + Source + Link | Signal path | Session + Device
            float cw = (contentW() - 2 * gap) / 3, xa = 0, xb = cw + gap, xc = 2 * (cw + gap);
            float ya = 0, yb = 0, yc = 0;
            drawMusic(c, sn, xa, ya, cw, musicH); ya += musicH + gap;
            drawSource(c, t, xa, ya, cw, sourceH); ya += sourceH + gap;
            drawLink(c, sn, xa, ya, cw, linkH); ya += linkH;
            drawSignal(c, t, xb, yb, cw, signalH); yb += signalH;
            drawSession(c, sn.session, xc, yc, cw, sessH); yc += sessH + gap;
            float deviceH = deviceHeight(sn.device, cw);
            drawDevice(c, sn.device, xc, yc, cw, deviceH); yc += deviceH;
            endContent(c, Math.max(ya, Math.max(yb, yc)));
            return;
        }
        float cw = (contentW() - gap) / 2, xa = 0, xb = cw + gap;
        float ya = 0, yb = 0;
        drawMusic(c, sn, xa, ya, cw, musicH); ya += musicH + gap;
        drawSource(c, t, xa, ya, cw, sourceH); ya += sourceH + gap;
        drawSession(c, sn.session, xa, ya, cw, sessH); ya += sessH + gap;
        drawLink(c, sn, xa, ya, cw, linkH); ya += linkH;
        drawSignal(c, t, xb, yb, cw, signalH); yb += signalH + gap;
        float deviceH = deviceHeight(sn.device, cw);
        drawDevice(c, sn.device, xb, yb, cw, deviceH); yb += deviceH;
        endContent(c, Math.max(ya, yb));
    }

    private float panel(Canvas c, String heading, float x, float y, float w, float h) {
        roundRect(c, x, y, w, h, 10, 0x0AFFFFFF);
        strokeRect(c, x + 0.5f, y + 0.5f, w - 1, h - 1, 10, 0x12FFFFFF, 1);
        tp.setTypeface(hanken600); tp.setTextSize(10 * s); tp.setColor(ACCENT); tp.setLetterSpacing(0.16f);
        c.drawText(heading.toUpperCase(Locale.US), X(x + 11), Y(y + 9 + 9), tp);
        tp.setLetterSpacing(0);
        return y + 9 + 12 + 6;
    }

    /** Height of one key/value row: 1 line if both fit, else the value wraps in full under the key (never cut off). */
    private float rowH(String key, String value, float w) {
        String v = value == null ? "N/A" : value;
        float kw = measure(key, 11.5f, hanken400), vw = measure(v, 11.5f, mono400);
        return kw + 8 + vw <= w ? 16.5f : 16.5f + wrapLines(v, w - 8, 11.5f).size() * 16.5f;
    }

    private float row(Canvas c, String key, String value, float x, float y, float w) {
        boolean na = value == null;
        String v = na ? "N/A" : value;
        int vc = na ? 0x4DFFFFFF : 0xEBFFFFFF;
        float kw = measure(key, 11.5f, hanken400), vw = measure(v, 11.5f, mono400);
        if (kw + 8 + vw <= w) {
            text(c, key, x, y + 12, 11.5f, hanken400, 0x80FFFFFF, w);
            text(c, v, x + w - vw, y + 12, 11.5f, mono400, vc, vw + 2);
            return y + 16.5f;
        }
        text(c, key, x, y + 12, 11.5f, hanken400, 0x80FFFFFF, w);
        float ly = y + 16.5f;
        for (String line : wrapLines(v, w - 8, 11.5f)) {      // left-aligned under the label, as many lines as it takes
            text(c, line.trim(), x + 8, ly + 12, 11.5f, mono400, vc, w - 8);
            ly += 16.5f;
        }
        return ly;
    }

    private float divider(Canvas c, float x, float y, float w) {
        p.reset(); p.setColor(0x14FFFFFF);
        c.drawRect(X(x), Y(y + 4), X(x + w), Y(y + 5), p);
        return y + 8;
    }

    private void drawMusic(Canvas c, Poller.Snapshot sn, float x, float y, float w, float h) {
        Track t = sn.track;
        float iy = panel(c, "Music", x, y, w, h), ix = x + 11, iw = w - 22;
        text(c, t.title == null ? "N/A" : t.title, ix, iy + 11, 12.5f, hanken600, Color.WHITE, iw);
        text(c, t.artist == null ? "N/A" : t.artist, ix, iy + 26, 11.5f, hanken400, 0xBFFFFFFF, iw);
        text(c, t.album == null ? "Album: N/A" : t.album, ix, iy + 41, 11.5f, hanken400, 0x8CFFFFFF, iw);
        float ty = iy + 48;
        if (!t.tags.isEmpty()) {
            float cx = ix;
            for (String tag : t.tags) {
                float tw = measure(tag, 10.5f, mono400) + 10;
                if (cx + tw > ix + iw) break;
                roundRect(c, cx, ty, tw, 15, 4, 0x24D9B48A);
                text(c, tag, cx + 5, ty + 11.5f, 10.5f, mono400, TAG_TEXT, tw);
                cx += tw + 4;
            }
            ty += 19;
        }
        float ry = ty;
        ry = row1(c, "File", sn.fileName, ix, ry, iw);
        ry = row1(c, "Size", sn.sizeBytes != null ? sn.sizeBytes : t.fileSize, ix, ry, iw);
        ry = row1(c, "Genre", sn.genre, ix, ry, iw);
        ry = row1(c, "Disc", sn.disc, ix, ry, iw);
        ry = row1(c, "Track ID", t.ownPlayer ? t.trackId : null, ix, ry, iw);
        ry = row1(c, "Album ID", t.ownPlayer && !"0".equals(t.albumId) ? t.albumId : null, ix, ry, iw);
        ry = row1(c, "Favourite", t.ownPlayer ? (t.favourite ? "Yes" : "No") : null, ix, ry, iw);
        ry = row1(c, "Lyrics", t.ownPlayer ? (t.lyrics ? "Yes" : "No") : null, ix, ry, iw);
        row1(c, "ReplayGain", t.replayGain, ix, ry, iw);
    }

    /** Single-line row (page 1 keeps the approved fixed layout; long values ellipsize). */
    private float row1(Canvas c, String key, String value, float x, float y, float w) {
        boolean na = value == null;
        String v = na ? "N/A" : value;
        text(c, key, x, y + 12, 11.5f, hanken400, 0x80FFFFFF, w * 0.5f);
        float kw = Math.min(measure(key, 11.5f, hanken400), w * 0.5f);
        tp.setTypeface(mono400); tp.setTextSize(11.5f * s);
        String shown = TextUtils.ellipsize(v, tp, (w - kw - 8) * s, TextUtils.TruncateAt.END).toString();
        float vw = tp.measureText(shown) / s;
        text(c, shown, x + w - vw, y + 12, 11.5f, mono400, na ? 0x4DFFFFFF : 0xEBFFFFFF, vw + 2);
        return y + 16.5f;
    }

    private void drawSource(Canvas c, Track t, float x, float y, float w, float h) {
        float ry = panel(c, "Source", x, y, w, h), ix = x + 11, iw = w - 22;
        ry = row1(c, "Player", t.playerLabel, ix, ry, iw);
        ry = row1(c, "Service", t.ownPlayer ? (t.service == null ? "Local library" : Track.pretty(t.service)) : t.playerLabel, ix, ry, iw);
        ry = row1(c, "Quality", t.quality, ix, ry, iw);
        ry = row1(c, "Seek", t.canSeek ? "Yes" : "No", ix, ry, iw);
        row1(c, "Queue", t.hasQueue ? "Yes" : "No", ix, ry, iw);
    }

    private void drawSignal(Canvas c, Track t, float x, float y, float w, float h) {
        float ry = panel(c, "Signal path", x, y, w, h), ix = x + 11, iw = w - 22;
        ry = row1(c, "Container", t.container, ix, ry, iw);
        ry = row1(c, "Codec", t.codec != null ? t.codec : (t.dsd ? Track.dsdName(t.rateHz) : t.container), ix, ry, iw);
        ry = row1(c, "Sample rate", t.rateHz == null ? null : Track.rateText(t.rateHz, t.dsd), ix, ry, iw);
        ry = row1(c, "Bit depth", t.bits == null ? null : Math.round(t.bits) + "-bit", ix, ry, iw);
        ry = row1(c, "Channels", t.channels == null ? null : String.valueOf(Math.round(t.channels)), ix, ry, iw);
        ry = row1(c, "Bitrate", t.bitrate, ix, ry, iw);
        ry = divider(c, ix, ry, iw);
        ry = row1(c, "Out codec", t.outCodec, ix, ry, iw);
        ry = row1(c, "Out rate", t.outRateHz == null ? null : Track.rateText(t.outRateHz, false), ix, ry, iw);
        ry = row1(c, "Out bit depth", null, ix, ry, iw);
        ry = row1(c, "Interface", t.iface, ix, ry, iw);
        ry = row1(c, "MQA", t.mqaOut ? "Yes" : (t.outCodec == null ? null : "No"), ix, ry, iw);
        ry = row1(c, "DSD", t.dsd ? (t.outCodec == null ? null : Track.isDsdCodec(t.outCodec) ? "Native" : "Converted") : null, ix, ry, iw);
        ry = row1(c, "SACD area", t.sacdArea, ix, ry, iw);
        ry = divider(c, ix, ry, iw);
        ry = row1(c, "EQ", t.eq ? (t.eqApplied() ? "On" : "On, bypassed") : "Off", ix, ry, iw);
        ry = row1(c, "DSP", t.dsp ? "On" : "Off", ix, ry, iw);
        ry = row1(c, "Subwoofer", t.sub ? "On" : "Off", ix, ry, iw);
        ry = divider(c, ix, ry, iw);
        ry = row1(c, "Volume", t.volume, ix, ry, iw);
        ry = row1(c, "Mute", t.mute ? "On" : "Off", ix, ry, iw);
        row1(c, "Volume lock", t.volumeLocked ? "Locked" : "Off", ix, ry, iw);
    }

    private void drawDevice(Canvas c, Poller.Device d, float x, float y, float w, float h) {
        float ry = panel(c, "Device", x, y, w, h), ix = x + 11, iw = w - 22;
        synchronized (d) {
            ry = row1(c, "Model", d.model, ix, ry, iw);
            ry = row1(c, "Firmware", d.firmware, ix, ry, iw);
            ry = row1(c, "Android", d.android, ix, ry, iw);
            ry = row1(c, "Memory", d.ram, ix, ry, iw);
            ry = row1(c, "Storage", d.flash, ix, ry, iw);
            ry = row1(c, "Brightness", d.brightness, ix, ry, iw);
            ry += 6;
            label(c, "INPUTS", ix, ry + 9, 10.5f);
            ry = chips(c, d.inputs, d.inputIndex, ix, ry + 14, iw);
            ry += 6;
            label(c, "OUTPUTS", ix, ry + 9, 10.5f);
            ry = chips(c, d.outputs, d.outputIndex, ix, ry + 14, iw);
            // 1.0.4: never above the last chip row (models with more inputs wrapped into the footer)
            float fy = Math.max(y + h - 9 - 44, ry + 6);
            p.reset(); p.setColor(0x14FFFFFF);
            c.drawRect(X(ix), Y(fy), X(ix + iw), Y(fy + 1), p);
            String active = d.outputIndex >= 0 && d.outputIndex < d.outputs.size() ? d.outputs.get(d.outputIndex) : "Output";
            text(c, active + " can do", ix, fy + 15, 10.5f, hanken500, 0x80FFFFFF, iw);
            text(c, d.capFormat == null ? "N/A" : d.capFormat, ix, fy + 29, 11, mono400, 0xEBFFFFFF, iw);
            text(c, d.capMax == null ? "" : d.capMax, ix, fy + 42, 11, mono400, 0x9EFFFFFF, iw);
        }
    }

    /**
     * Height the Overview's Device panel needs: 300 on the A6, taller when a model lists more inputs or outputs
     * than fit on the A6's rows (1.0.4; seen on a model with six inputs). Mirrors drawDevice's layout:
     * header 27, six rows of 16.5, two chip blocks with their labels, then the "can do" footer.
     */
    private float deviceHeight(Poller.Device d, float w) {
        float iw = w - 22;
        synchronized (d) {
            return Math.max(300, 27 + 6 * 16.5f + 6 + 14 + chipsHeight(d.inputs, d.inputIndex, iw)
                + 6 + 14 + chipsHeight(d.outputs, d.outputIndex, iw) + 6 + 44 + 9);
        }
    }

    /** Height of a chip block, wrapped exactly as chips() wraps it. */
    private float chipsHeight(List<String> names, int active, float w) {
        float cx = 0, rows = 1;
        for (int i = 0; i < names.size(); i++) {
            String lbl = (i == active ? "● " : "") + names.get(i);
            float cw = measure(lbl, 10.5f, mono400) + 12;
            if (cx > 0 && cx + cw > w) { cx = 0; rows++; }
            cx += cw + 4;
        }
        return rows * 19;
    }

    private float chips(Canvas c, List<String> names, int active, float x, float y, float w) {
        float cx = x, cy = y;
        for (int i = 0; i < names.size(); i++) {
            boolean on = i == active;
            String lbl = (on ? "● " : "") + names.get(i);
            float cw = measure(lbl, 10.5f, mono400) + 12;
            if (cx > x && cx + cw > x + w) { cx = x; cy += 19; }
            if (on) roundRect(c, cx, cy, cw, 16, 4, CHIP_GREEN);
            else strokeRect(c, cx + 0.5f, cy + 0.5f, cw - 1, 15, 4, 0x29FFFFFF, 1);
            text(c, lbl, cx + 6, cy + 12, 10.5f, mono400, on ? 0xFF0B1A10 : 0x99FFFFFF, cw);
            cx += cw + 4;
        }
        return cy + 19;
    }

    private void drawSession(Canvas c, Session ss, float x, float y, float w, float h) {
        float ry = panel(c, "Session · today", x, y, w, h), ix = x + 11, iw = w - 22;
        if (ss == null) return;
        long mins = ss.listenedMs / 60000;
        String time = mins >= 60 ? (mins / 60) + " h " + (mins % 60) + " m" : mins + " m";
        text(c, time, ix, ry + 20, 22, mono500, Color.WHITE, iw);
        text(c, "listened · " + ss.tracks + (ss.tracks == 1 ? " track" : " tracks"), ix, ry + 36, 11, hanken400, 0x9EFFFFFF, iw);
        float by = ry + 46;
        long total = ss.hiresMs + ss.cdMs + ss.dsdMs + ss.lossyMs;
        long[] parts = {ss.hiresMs, ss.cdMs, ss.dsdMs, ss.lossyMs};
        int[] cols = {GREEN, BLUE, AMBER, ORANGE};
        String[] names = {"Hi-res", "CD", "DSD", "Lossy"};
        if (total > 0) {
            float bx = ix;
            int n = 0;
            for (long v : parts) if (v > 0) n++;
            float avail = iw - 2 * Math.max(0, n - 1);
            for (int i = 0; i < 4; i++) {
                if (parts[i] <= 0) continue;
                float sw = avail * parts[i] / total;
                roundRect(c, bx, by, sw, 6, 3, cols[i]);
                bx += sw + 2;
            }
        } else roundRect(c, ix, by, iw, 6, 3, 0x1AFFFFFF);
        float ly = by + 14;
        for (int i = 0; i < 4; i++) {
            float lx = ix + (i % 2) * (iw / 2);
            float yy = ly + (i / 2) * 16;
            p.reset(); p.setAntiAlias(true); p.setColor(cols[i]);
            c.drawCircle(X(lx + 3), Y(yy + 5), 3 * s, p);
            String pct = total > 0 ? Math.round(parts[i] * 100.0 / total) + "%" : "–";
            text(c, names[i] + " " + pct, lx + 10, yy + 9, 10.5f, hanken400, 0xBFFFFFFF, iw / 2 - 10);
        }
        float hy = ly + 40;
        label(c, "HIGHEST TODAY", ix, hy + 9, 10.5f);
        String top = ss.maxDsdHz > 0 ? Track.dsdName(ss.maxDsdHz) + " · " + Track.rateText(ss.maxDsdHz, true)
            : ss.maxPcmHz > 0 ? "PCM " + Track.rateText(ss.maxPcmHz, false) : "N/A";
        text(c, top, ix, hy + 26, 12, mono500, Color.WHITE, iw);
        if (ss.maxDsdHz > 0 && ss.maxPcmHz > 0) text(c, "PCM " + Track.rateText(ss.maxPcmHz, false), ix, hy + 41, 11, mono400, 0x9EFFFFFF, iw);
        float ry2 = hy + 54;
        label(c, "BITRATE TODAY", ix, ry2 + 9, 10.5f);
        String br = ss.maxBps > 0 ? Track.bpsText(ss.minBps) + " – " + Track.bpsText(ss.maxBps) : "N/A";
        text(c, br, ix, ry2 + 26, 12, mono500, Color.WHITE, iw);
        text(c, "lowest – highest file", ix, ry2 + 40, 10.5f, hanken400, 0x8CFFFFFF, iw);
    }

    private void drawLink(Canvas c, Poller.Snapshot sn, float x, float y, float w, float h) {
        float ry = panel(c, "Link", x, y, w, h), ix = x + 11, iw = w - 22;
        ry = row1(c, "Refresh", sn.online ? sn.rttMs + " ms" : null, ix, ry, iw);
        long ago = sn.lastUpdate == 0 ? -1 : (System.currentTimeMillis() - sn.lastUpdate) / 1000;
        ry = row1(c, "Last update", ago < 0 ? null : ago + " s ago", ix, ry, iw);
        float ey = ry;
        ry = row1(c, "Errors", String.valueOf(sn.errors), ix, ry, iw);
        if (sn.errors == 0) {
            tp.setTypeface(mono400); tp.setTextSize(11.5f * s);
            float vw = tp.measureText("0") / s;
            text(c, "0", ix + iw - vw, ey + 12, 11.5f, mono400, GREEN, vw + 2);
        }
        // 1.0.10: Stay in front moved to the Settings & about page (it was hard to find here)
    }

    // ================================================================ Detail carousel: stats pages

    /**
     * A block on a stats page. Placement (fill the screen above the fold):
     * FULL = full width across the top; FLOW = into the currently shortest column; STICK = right under the
     * section before it (same column) when that still fits the screen, else into the shortest column.
     * Sections are never hidden: N/A rows stay ("stats for nerds ... another source might have it").
     */
    static final int FULL = -1, FLOW = 0, STICK = 1;

    private static final class Section {
        final String title;
        final int kind;
        boolean list;                                       // chips drawn one per row (Track flags only)
        final List<String[]> rows = new ArrayList<>();
        final List<String> text = new ArrayList<>();
        final List<String[]> wraps = new ArrayList<>();     // {key, value}: value wrapped in full
        final List<String[]> chips = new ArrayList<>();     // {label, raw or null, "true"/"false"}
        Section(String title, int kind) { this.title = title; this.kind = kind; }
        Section row(String k, String v) { rows.add(new String[]{k, v}); return this; }
        Section wrap(String k, String v) { if (v != null) wraps.add(new String[]{k, v}); return this; }
        boolean empty() { return rows.isEmpty() && text.isEmpty() && wraps.isEmpty() && chips.isEmpty(); }
    }

    private void drawDots(Canvas c) {
        float d = 7, gap = 10, total = CAROUSEL * d + (CAROUSEL - 1) * gap;
        float x = (vw - total) / 2f, y = 469;
        if (page == FEEDBACK_PAGE) { text(c, PAGES[page], 16, y + 8, 13, hanken500, 0x99FFFFFF, 330); return; }
        for (int i = 0; i < CAROUSEL; i++) {
            p.reset(); p.setAntiAlias(true); p.setColor(i == page ? LIGHT : 0x40FFFFFF);
            c.drawCircle(X(x + d / 2), Y(y + d / 2), d / 2 * s, p);
            final int target = i;
            hit(x - 4, y - 6, d + 8, d + 12, () -> { page = target; invalidate(); });
            x += d + gap;
        }
        text(c, (page + 1) + " / " + CAROUSEL + "  " + PAGES[page], 16, y + 8, 13, hanken500, 0x99FFFFFF, 330);
        if (page == 0) text(c, "swipe for more →", vw - 16 - measure("swipe for more →", 13, hanken500), y + 8, 13, hanken500, 0x73FFFFFF, 220);
    }

    private void drawStatsPage(Canvas c, Poller.Snapshot sn) {
        List<Section> sections;
        switch (page) {
            case 1: sections = trackPage(sn); break;
            case 2: sections = queuePage(sn); break;
            case 3: sections = devicePage(sn); break;
            default: sections = ioPage(sn);
        }
        // virtual (zoomed) coordinates: 0..contentW() wide, y from the top of the scrolled content
        final int n = columns();
        final float gap = 8, fullW = contentW(), colW = (fullW - (n - 1) * gap) / n, viewH = contentH();
        float off = scroll[page];
        beginContent(c);
        float fy = 0;
        for (Section sec : sections) {
            if (sec.kind != FULL) continue;
            float h = sectionHeight(sec, fullW);
            drawSection(c, sec, 0, fy, fullW, h);
            fy += h + gap;
        }
        float[] colY = new float[n];
        java.util.Arrays.fill(colY, fy);
        int prevCol = -1;
        for (Section sec : sections) {
            if (sec.kind == FULL) continue;
            float h = sectionHeight(sec, colW);
            int col = shortest(colY);
            if (sec.kind == STICK && prevCol >= 0 && colY[prevCol] + h <= viewH) col = prevCol;
            float x = col * (colW + gap), y = colY[col];
            if (y + h > off - 20 && y < off + viewH + 20) drawSection(c, sec, x, y, colW, h);
            colY[col] += h + gap;
            prevCol = col;
        }
        float bottom = 0;
        for (float y : colY) bottom = Math.max(bottom, y);
        endContent(c, bottom);
    }

    /** The column with the least content so far; ties go to the leftmost (keeps reading order). */
    private static int shortest(float[] colY) {
        int best = 0;
        for (int i = 1; i < colY.length; i++) if (colY[i] < colY[best] - 0.5f) best = i;
        return best;
    }

    private static final float LIST_ROW = 29;

    private List<String> wrapLines(String v, float widthDp, float size) {
        List<String> out = new ArrayList<>();
        tp.setTypeface(mono400); tp.setTextSize(size * s);
        String rest = v;
        while (!rest.isEmpty()) {
            int n = tp.breakText(rest, true, widthDp * s, null);
            if (n <= 0) break;
            if (n < rest.length()) {            // prefer a word break; URLs/paths without spaces still break anywhere
                int sp = rest.lastIndexOf(' ', n);
                if (sp > n / 2) n = sp + 1;
            }
            out.add(rest.substring(0, n));
            rest = rest.substring(n);
        }
        return out;
    }

    private static final float CHIP_H = 34, CHIP_GAP = 4;

    private float chipW(String[] ch) {
        float a = measure(ch[0], 11, hanken500), b = ch[1] == null ? 0 : measure(ch[1], 10, mono400);
        return Math.max(a, b) + 14;
    }

    private int chipRows(List<String[]> chips, float w) {
        int rows = 1;
        float cx = 0;
        for (String[] ch : chips) {
            float cw = chipW(ch);
            if (cx + cw > w && cx > 0) { rows++; cx = 0; }
            cx += cw + CHIP_GAP;
        }
        return rows;
    }

    private float sectionHeight(Section sec, float w) {
        float iw = w - 22, h = 27 + 9;
        h += sec.text.size() * 15;
        for (String[] r : sec.rows) h += rowH(r[0], r[1], iw);
        for (String[] wr : sec.wraps) h += (wr[0] == null ? 0 : 15) + wrapLines(wr[1], iw, 11).size() * 14 + 2;
        if (!sec.chips.isEmpty()) h += sec.list ? sec.chips.size() * LIST_ROW + 2 : chipRows(sec.chips, iw) * (CHIP_H + CHIP_GAP) + 2;
        if (sec.empty()) h += 15;
        return h;
    }

    private void drawSection(Canvas c, Section sec, float x, float y, float w, float h) {
        float ry = panel(c, sec.title, x, y, w, h), ix = x + 11, iw = w - 22;
        for (String[] wr : sec.wraps) {
            if (wr[0] != null) { text(c, wr[0], ix, ry + 11, 11.5f, hanken400, 0x80FFFFFF, iw); ry += 15; }
            int q = wr[1].indexOf('?');     // the query part (a signed token) is drawn dimmer
            int at = 0;
            for (String line : wrapLines(wr[1], iw, 11)) {
                float lx = ix;
                tp.setTypeface(mono400); tp.setTextSize(11 * s);
                int split = q < 0 ? line.length() : Math.max(0, Math.min(line.length(), q - at));
                String a = line.substring(0, split), b = line.substring(split);
                if (!a.isEmpty()) { tp.setColor(0xEBFFFFFF); c.drawText(a, X(lx), Y(ry + 11), tp); lx += tp.measureText(a) / s; }
                if (!b.isEmpty()) { tp.setColor(0x66FFFFFF); c.drawText(b, X(lx), Y(ry + 11), tp); }
                at += line.length();
                ry += 14;
            }
            ry += 2;
        }
        for (String line : sec.text) {
            int tab = line.indexOf('	');
            if (tab < 0) { text(c, line, ix, ry + 11, 11, mono400, 0xEBFFFFFF, iw); ry += 15; continue; }
            // "title · artist" on the left (cut with … if needed), the format right-aligned and dimmer
            String right = line.substring(tab + 1);
            float rw = right.isEmpty() ? 0 : measure(right, 11, mono400);
            text(c, line.substring(0, tab), ix, ry + 11, 11, mono400, 0xEBFFFFFF, iw - rw - (rw > 0 ? 10 : 0));
            if (rw > 0) text(c, right, ix + iw - rw, ry + 11, 11, mono400, 0x99FFFFFF, rw + 2);
            ry += 15;
        }
        for (String[] r : sec.rows) ry = row(c, r[0], r[1], ix, ry, iw);
        if (sec.empty()) text(c, "None reported", ix, ry + 11, 11, mono400, 0x4DFFFFFF, iw);
        if (sec.list) {
            // one flag per row: pretty name, raw name dimmer under it, state on the right (Track flags)
            float cy = ry + 2;
            for (String[] ch : sec.chips) {
                boolean on = "true".equalsIgnoreCase(ch[2]);
                String state = on ? "Yes" : "No";
                float sw = measure(state, 11, mono500);
                text(c, ch[0], ix, cy + 11, 11, hanken500, on ? 0xEBFFFFFF : 0x99FFFFFF, iw - sw - 12);
                if (ch[1] != null) text(c, ch[1], ix, cy + 24, 10, mono400, 0x73FFFFFF, iw - sw - 12);
                text(c, state, ix + iw - sw, cy + 15, 11, mono500, on ? GREEN : 0x66FFFFFF, sw + 2);
                cy += LIST_ROW;
            }
            return;
        }
        if (!sec.chips.isEmpty()) {
            float cx = ix, cy = ry + 2;
            for (String[] ch : sec.chips) {
                boolean on = "true".equalsIgnoreCase(ch[2]);
                float cw = chipW(ch);
                if (cx + cw > ix + iw && cx > ix) { cx = ix; cy += CHIP_H + CHIP_GAP; }
                if (on) roundRect(c, cx, cy, cw, CHIP_H, 5, 0x3D4CC27A);
                else strokeRect(c, cx + 0.5f, cy + 0.5f, cw - 1, CHIP_H - 1, 5, 0x24FFFFFF, 1);
                boolean two = ch[1] != null;
                text(c, ch[0], cx + 7, cy + (two ? 14 : 21.5f), 11, hanken500, on ? GREEN : 0x99FFFFFF, cw);
                if (two) text(c, ch[1], cx + 7, cy + 28, 10, mono400, on ? 0xA65FD08B : 0x73FFFFFF, cw);
                cx += cw + CHIP_GAP;
            }
        }
    }

    private static String v(Map<String, String> m, String k) { return m == null ? null : Flat.str(m, k); }

    /** Flag chips. pretty = pretty name with the raw name underneath (flags only). */
    private static void flags(Section sec, Map<String, String> m, String prefix, boolean pretty, String... exclude) {
        if (m == null) return;
        outer:
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!e.getKey().startsWith(prefix) || !Raw.isBool(e.getValue())) continue;
            for (String x : exclude) if (e.getKey().startsWith(x)) continue outer;
            String raw = Raw.shortKey(e.getKey());
            String name = pretty ? Names.of(raw) : null;
            sec.chips.add(name == null ? new String[]{raw, null, e.getValue()} : new String[]{name, raw, e.getValue()});
        }
    }

    /** Remaining non-flag values under prefix, as raw rows (device names, as reported). */
    private static void rest(Section sec, Map<String, String> m, String prefix, Set<String> used, String... exclude) {
        if (m == null) return;
        outer:
        for (Map.Entry<String, String> e : m.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(prefix) || used.contains(k) || Raw.isBool(e.getValue())) continue;
            for (String x : exclude) if (k.startsWith(x) || k.toLowerCase(Locale.US).contains(x.toLowerCase(Locale.US))) continue outer;
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            sec.row(k.substring(prefix.length()), e.getValue());
        }
    }

    private static Set<String> set(String... keys) { return new java.util.HashSet<>(java.util.Arrays.asList(keys)); }
    private static String nz(String v) { return v == null || "0".equals(v) || "-1".equals(v) ? null : v; }

    // ---- page 2: Track & file
    private List<Section> trackPage(Poller.Snapshot sn) {
        List<Section> out = new ArrayList<>();
        Track t = sn.track;
        Map<String, String> q = sn.queueItem, st = sn.stateRaw;

        Section from = new Section("Playing from", FULL);
        from.wrap(null, sn.playingFrom);
        String service = t.ownPlayer ? (t.service == null ? "Local library" : Track.pretty(t.service)) : t.playerLabel;
        if (t.playerLabel != null && t.playerLabel.equals(service)) from.row("Source", service);   // one row, not two
        else from.row("Player", t.playerLabel).row("Service", service);
        if (sn.audioHost != null) {
            boolean airable = sn.playingFrom != null && sn.playingFrom.contains("airable.io");
            String svc = t.service == null ? "the service" : Track.pretty(t.service);
            from.row("Route", (airable ? "Requested via Airable → audio from " + svc : "Audio from " + svc) + " (" + sn.audioHost + ")");
        }
        if (sn.playingFrom == null) from.row("Path", null);
        out.add(from);

        Section credits = new Section("Credits", FLOW);
        credits.row("Title", t.title).row("Artist", t.artist).row("Album artist", v(q, "albumArtist"))
            .row("Album", v(q, "album") != null ? v(q, "album") : t.album).row("Composer", v(q, "composer"))
            .row("Year", v(q, "date")).row("Genre", v(q, "genreName")).row("Track no.", nz(v(q, "number")))
            .row("Disc", nz(v(q, "diskNumber")));
        if (!t.tags.isEmpty()) credits.row("Edition", String.join(" · ", t.tags));
        out.add(credits);

        Section file = new Section("File", STICK);
        // Two bitrates from two places (e.g. 927,060 bps vs 529.71 kbps on one track): the library's exact
        // figure, and the player's own report, which can lag or differ. Labelled by source, not merged.
        file.row("File name", v(q, "fileName")).row("Type", v(q, "displayExtension") != null ? v(q, "displayExtension") : t.container)
            .row("Size", Raw.bytes(v(q, "fileSize")) != null ? Raw.bytes(v(q, "fileSize")) : t.fileSize)
            .row("Exact bitrate (library)", Raw.bps(v(q, "bitrate"))).row("Bitrate (player)", t.bitrate)
            .row("Codec", v(q, "codec") != null ? v(q, "codec") : t.codec)
            .row("Modified", Raw.date(v(q, "modifiedTime")));
        if (t.rateHz != null && t.bits != null && t.channels != null && !t.dsd && !t.lossy) {
            double raw = t.rateHz * t.bits * t.channels / 1e6;
            Double br = sn.exactBps != null ? sn.exactBps / 1e6 : t.bitrateBps == null ? null : t.bitrateBps / 1e6;
            file.row("Uncompressed rate", String.format(Locale.US, "%.2f Mbps", raw));
            if (br != null && br > 0 && br < raw) file.row("Compression", Math.round(100 - br * 100 / raw) + "% smaller than uncompressed (lossless)");
        }
        out.add(file);

        Section lib = new Section("Library", FLOW);
        lib.row("Added", Raw.date(v(q, "addedTime"))).row("Play count", v(q, "playTimes"))
            .row("Last played", q == null ? null : Raw.date(v(q, "lastPlayTime")) == null ? "never" : Raw.date(v(q, "lastPlayTime")))
            .row("Favourite", t.ownPlayer ? (t.favourite ? "Yes" : "No") : null).row("Track ID", v(q, "id") != null ? v(q, "id") : t.trackId)
            .row("Database ID", nz(v(q, "databaseId"))).row("Album ID", nz(v(q, "albumId"))).row("Folder ID", nz(v(q, "folderId")))
            .row("MusicBrainz release", v(q, "releaseMid"))
            .row("CUE track", q == null ? null : "true".equals(v(q, "isLocalCueSong")) ? "#" + v(q, "cueindex") : "No")
            .row("ReplayGain (album)", v(q, "replayGainAlbum")).row("MQA ReplayGain", v(q, "MQAReplayGain"));
        out.add(lib);

        {   // always shown, N/A when the source has no library entry
            Section other = new Section("Other library fields", STICK);
            Set<String> shown = set("albumArtist", "album", "composer", "date", "genreName", "number", "diskNumber", "fileName",
                "displayExtension", "fileSize", "bitrate", "codec", "modifiedTime", "addedTime", "playTimes", "lastPlayTime", "id",
                "databaseId", "albumId", "folderId", "releaseMid", "cueindex", "replayGainAlbum", "MQAReplayGain", "title", "artist",
                "path", "url", "uri", "albumArt");
            rest(other, q, "", shown);
            out.add(other);
        }

        Section flagsSec = new Section("Track flags", FLOW);
        flagsSec.list = true;
        flags(flagsSec, st, "playingMusic.", true);
        if (q != null) for (Map.Entry<String, String> e : q.entrySet()) {
            if (!Raw.isBool(e.getValue())) continue;
            String name = Names.of(e.getKey());
            flagsSec.chips.add(name == null ? new String[]{e.getKey(), null, e.getValue()} : new String[]{name, e.getKey(), e.getValue()});
        }
        out.add(flagsSec);
        return out;
    }

    // ---- page 3: Queue & playback
    private List<Section> queuePage(Poller.Snapshot sn) {
        List<Section> out = new ArrayList<>();
        Track t = sn.track;
        Map<String, String> st = sn.stateRaw;
        Section q = new Section("Queue", FLOW);
        q.row("Position", sn.queuePos > 0 ? sn.queuePos + (sn.queueTotal > 0 ? " of " + sn.queueTotal : "") : null)
            .row("Queue length", sn.queueDurationMs > 0 ? hm(sn.queueDurationMs) : null)
            .row("Queue available", t.hasQueue ? "Yes" : "No");
        out.add(q);
        Section next = new Section("Up next", STICK);
        for (String[] n : sn.upNext) {
            next.text.add(n[0] + (n[1] == null || n[1].isEmpty() ? "" : " · " + n[1]) + "	" + (n[2] == null ? "" : n[2]));
        }
        if (sn.upNext.isEmpty()) next.row("Next", null);
        out.add(next);

        Section play = new Section("Playback state", FLOW);
        String state = t.state == 3 ? "Playing" : t.state == 4 ? "Paused" : "Stopped";
        play.row("State", state + " (" + v(st, "state") + ")").row("Position", mss(t.positionMs) + " / " + mss(t.durationMs))
            .row("playType", v(st, "playType")).row("Source label", v(st, "everSoloPlayInfo.playTypeSubtitle"))
            .row("loopModel", v(st, "loopModel")).row("playMode", v(st, "everSoloPlayInfo.playMode"))
            .row("trackIndex", v(st, "trackIndex")).row("currentOutput", v(st, "currentOutput"))
            .row("vuDisplayMode", v(st, "vuDisplayMode")).row("spDisplayMode", v(st, "spDisplayMode"))
            .row("mqaMode", v(st, "mqaMode"));
        out.add(play);
        Section audio = new Section("Connect source (raw)", FLOW);
        rest(audio, st, "everSoloPlayInfo.everSoloPlayAudioInfo.", set(), "url");
        rest(audio, st, "everSoloPlayInfo.everSoloPlayOutputInfo.", set());
        out.add(audio);
        Section other = new Section("Other state values", FLOW);
        Set<String> used = set("state", "playType", "loopModel", "trackIndex", "currentOutput", "vuDisplayMode", "spDisplayMode",
            "mqaMode", "position", "duration", "everSoloPlayInfo.playMode", "everSoloPlayInfo.playTypeSubtitle");
        rest(other, st, "", used, "playingMusic.", "volumeData.", "deviceInfo.", "everSoloPlayInfo.everSoloPlay");
        out.add(other);

        Section f1 = new Section("Player flags", FLOW);
        flags(f1, st, "", true, "playingMusic.", "volumeData.", "deviceInfo.", "everSoloPlayInfo.");
        out.add(f1);
        Section f2 = new Section("Now-playing screen flags", FLOW);
        flags(f2, st, "everSoloPlayInfo.", true);
        out.add(f2);
        return out;
    }

    private static String hm(long ms) {
        long m = ms / 60000;
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " m" : m + " m";
    }

    // ---- page 4: Device
    private List<Section> devicePage(Poller.Snapshot sn) {
        List<Section> out = new ArrayList<>();
        Map<String, String> m = sn.modelRaw, st = sn.stateRaw;
        Section dev = new Section("Device", FLOW);
        dev.row("Model", v(m, "model")).row("Display model", v(m, "disModel")).row("Name", v(m, "deviceName"))
            .row("Firmware", v(m, "firmware"))
            // 1.0.3: screen size, so owners of other models can tell us what to lay out for
            .row("Screen", screenSize()).row("App area", appArea()).row("EverSignal", BuildConfig.VERSION_NAME).row("Android", v(m, "androidversion")).row("Memory", v(m, "ram"))
            .row("Storage", v(m, "flash")).row("IP address", v(m, "ip")).row("Language", v(m, "language"))
            .row("Region code", v(m, "nationcode")).row("App code", v(m, "appcode")).row("Device type", v(m, "dType"))
            // shown on purpose: useful to the owner. Mask them before sharing a screenshot.
            .row("Ethernet MAC", v(m, "net_mac")).row("Wi-Fi MAC", v(m, "wif_mac")).row("Device ID", v(m, "duuid"));
        out.add(dev);
        Section info = new Section("Device info (from state)", STICK);
        rest(info, st, "deviceInfo.", set());
        out.add(info);
        Section disp = new Section("Display", STICK);
        if (sn.displayRaw != null) for (Map.Entry<String, String> e : sn.displayRaw.entrySet()) {
            if (e.getKey().endsWith("styles")) disp.wrap(e.getKey(), e.getValue()); else disp.row(e.getKey(), e.getValue());
        }
        out.add(disp);
        Section caps = new Section("Capabilities", FLOW);
        flags(caps, m, "", true, "device.");
        out.add(caps);
        Section other = new Section("Other model fields", FLOW);
        // configUrl not shown: it points to the vendor's service catalog
        rest(other, m, "", set("model", "disModel", "deviceName", "firmware", "androidversion", "ram", "flash", "ip", "language",
            "nationcode", "appcode", "dType", "status", "configUrl", "net_mac", "wif_mac", "duuid"));
        out.add(other);
        return out;
    }

    // ---- page 5: Inputs, outputs & volume
    private List<Section> ioPage(Poller.Snapshot sn) {
        List<Section> out = new ArrayList<>();
        Map<String, String> io = sn.ioRaw, st = sn.stateRaw;
        Double ii = Flat.num(v(io, "inputIndex")), oi = Flat.num(v(io, "outputIndex"));
        int inIdx = ii == null ? -1 : ii.intValue(), outIdx = oi == null ? -1 : oi.intValue();

        Section ins = new Section("Inputs", FLOW);
        // state first, the API's tag after it (the tag alone is not meaningful)
        for (int i = 0; v(io, "inputData[" + i + "].name") != null; i++) {
            String state = i == inIdx ? "Active" : "Available";
            ins.row((i == inIdx ? "● " : "") + v(io, "inputData[" + i + "].name"), state);
        }
        out.add(ins);
        Section outs = new Section("Outputs", STICK);
        for (int i = 0; v(io, "outputData[" + i + "].name") != null; i++) {
            String en = v(io, "outputData[" + i + "].enable");
            String state = i == outIdx ? "Active" : "false".equals(en) ? "Disabled" : "Enabled";
            outs.row((i == outIdx ? "● " : "") + v(io, "outputData[" + i + "].name"), state);
        }
        out.add(outs);

        // the active output's own settings page, as the player reports it (read-only, get-address only)
        if (sn.outputSettings != null) {
            String t = sn.outputSettingsTitle == null ? "" : " (" + sn.outputSettingsTitle.replaceAll("(?i)\\s*output$", "") + ")";
            Section os = new Section("Output settings" + t, STICK);
            for (String[] r : sn.outputSettings) os.row(r[0], r[1]);
            out.add(os);
        }

        Section act = new Section("Active output", FLOW);
        boolean hasList = v(io, "outputInfo.infolist[0].title") != null;
        for (int i = 0; v(io, "outputInfo.infolist[" + i + "].title") != null; i++) {
            act.row(v(io, "outputInfo.infolist[" + i + "].title").replace("：", "").replace(":", ""), v(io, "outputInfo.infolist[" + i + "].value"));
        }
        rest(act, io, "outputInfo.", hasList ? set("outputInfo.format", "outputInfo.sampleRate") : set(), "infolist");
        flags(act, io, "outputInfo.", false);
        out.add(act);

        // each chip names its input/output (otherwise it just reads "enabled" five times)
        Section ioFlags = new Section("Input & output flags", FLOW);
        for (int i = 0; v(io, "inputData[" + i + "].name") != null; i++) {
            String e = v(io, "inputData[" + i + "].isEdit");
            if (e != null) ioFlags.chips.add(new String[]{v(io, "inputData[" + i + "].name") + " · " + ("true".equals(e) ? "editable" : "not editable"),
                "inputData[" + i + "].isEdit", e});
        }
        for (int i = 0; v(io, "outputData[" + i + "].name") != null; i++) {
            String e = v(io, "outputData[" + i + "].enable");
            if (e != null) ioFlags.chips.add(new String[]{v(io, "outputData[" + i + "].name") + " · " + ("true".equals(e) ? "enabled" : "disabled"),
                "outputData[" + i + "].enable", e});
        }
        out.add(ioFlags);

        Section vol = new Section("Volume & input", FLOW);
        rest(vol, st, "volumeData.", set());
        out.add(vol);
        Section vflags = new Section("Volume flags", STICK);
        flags(vflags, st, "volumeData.", true);
        out.add(vflags);
        return out;
    }

    // ================================================================ shared pieces

    private void drawCover(Canvas c, Bitmap b, float x, float y, float size, float radius, boolean shadow) {
        RectF r = new RectF(X(x), Y(y), X(x + size), Y(y + size));
        if (shadow) {
            p.reset(); p.setAntiAlias(true); p.setColor(0x8C000000);
            p.setMaskFilter(new BlurMaskFilter(30 * s, BlurMaskFilter.Blur.NORMAL));
            c.drawRoundRect(new RectF(r.left, r.top + 24 * s, r.right, r.bottom + 24 * s), radius * s, radius * s, p);
            p.setMaskFilter(null);
        }
        p.reset(); p.setAntiAlias(true);
        if (b != null) {
            BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float sc = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
            Matrix m = new Matrix();
            m.setScale(sc, sc);
            m.postTranslate(r.left + (r.width() - b.getWidth() * sc) / 2f, r.top + (r.height() - b.getHeight() * sc) / 2f);
            sh.setLocalMatrix(m);
            p.setShader(sh);
        } else p.setColor(0xFF1C1A19);
        c.drawRoundRect(r, radius * s, radius * s, p);
        p.setShader(null);
        if (b != null && frame) {
            // 1.0.13 (Simple v4): the frame is an inner line, 2 wide, in a LIGHTER colour taken from the art
            float[] hsv = new float[3];
            Color.colorToHSV(frameColour(b), hsv);
            hsv[2] = Math.min(0.9f, Math.max(0.6f, hsv[2] * 1.45f));
            float sw = size >= 100 ? 2f : 1.5f;
            strokeRect(c, x + sw / 2, y + sw / 2, size - sw, size - sw, radius, Color.HSVToColor(hsv), sw);
        }
        strokeRect(c, x + 0.5f, y + 0.5f, size - 1, size - 1, radius, 0x0FFFFFFF, 1);
    }

    /**
     * 1.0.12: a frame colour that belongs to the picture. Václav's example (Whitesnake "Trouble") is a white cover in a
     * deep red frame taken from the art, not from the white edge, so the most saturated colours weigh most; a
     * colourless cover falls back to its average. Kept mid-dark so the frame reads as an edge, not a second picture.
     */
    private int frameColour(Bitmap b) {
        Integer known = frameColours.get(b);
        if (known != null) return known;
        Bitmap sm = Bitmap.createScaledBitmap(b, 24, 24, true);
        float[] hsv = new float[3];
        double wr = 0, wg = 0, wb = 0, wt = 0, ar = 0, ag = 0, ab = 0;
        for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
            int px = sm.getPixel(x, y);
            Color.colorToHSV(px, hsv);
            double w = hsv[1] * hsv[1] * Math.max(0.2, hsv[2]);
            wr += Color.red(px) * w; wg += Color.green(px) * w; wb += Color.blue(px) * w; wt += w;
            ar += Color.red(px); ag += Color.green(px); ab += Color.blue(px);
        }
        int col = wt > 24 * 24 * 0.04
            ? Color.rgb((int) (wr / wt), (int) (wg / wt), (int) (wb / wt))
            : Color.rgb((int) (ar / 576), (int) (ag / 576), (int) (ab / 576));
        Color.colorToHSV(col, hsv);
        hsv[2] = Math.min(0.72f, Math.max(0.30f, hsv[2] * 0.85f));
        int out = Color.HSVToColor(hsv);
        frameColours.put(b, out);
        return out;
    }

    /**
     * 1.0.12: full-screen album art. The picture fits the screen above a reduced stats line (Michael: "a reduced
     * player stats line below it... simpler version, key stats"): badge, format, time, then title and artist.
     */
    private void drawViewer(Canvas c, Poller.Snapshot sn) {
        long now = System.currentTimeMillis();
        int n = viewer.size();
        if (n > 1 && now >= vNextAt) viewerStep(1);
        Bitmap b = viewer.get(vIdx);
        if (b == null && viewer.failed(vIdx) && n > 1) { viewerStep(1); b = viewer.get(vIdx); }

        p.reset(); p.setColor(0xFF000000);
        c.drawRect(0, 0, getWidth(), getHeight(), p);

        float top = 12, bottom = 408, l = 16, r = vw - 16;
        if (b != null) {
            float aw = r - l, ah = bottom - top;
            float sc = Math.min(aw / b.getWidth(), ah / b.getHeight());
            float w = b.getWidth() * sc, h = b.getHeight() * sc, x = l + (aw - w) / 2, y = top + (ah - h) / 2;
            if (frame) {
                float bw = Math.max(3f, Math.min(w, h) * 0.012f);
                roundRect(c, x - bw, y - bw, w + 2 * bw, h + 2 * bw, 2, frameColour(b));
            }
            p.reset(); p.setAntiAlias(true); p.setFilterBitmap(true);
            c.drawBitmap(b, null, new RectF(X(x), Y(y), X(x + w), Y(y + h)), p);
        } else if (n == 0) {
            drawCentered(c, "No album art for this track", 0, 200, vw, 30, 18, hanken500, 0x99FFFFFF);
        }

        // 1.0.12: lyrics over the dimmed art, and the LYRICS button (top left) when the track has an .lrc
        if (lyrics != null) {
            if (lyricsOn && sn != null && sn.track != null) {
                p.reset(); p.setColor(0xC0000000);
                c.drawRect(X(0), Y(0), X(vw), Y(bottom + 4), p);
                drawLyrics(c, sn, top + 40, bottom - 10);
            }
            float bw = measure("LYRICS", 14, hanken600) + 28;
            if (lyricsOn) roundRect(c, 20, 20, bw, 34, 17, LIGHT);
            else { roundRect(c, 20, 20, bw, 34, 17, 0xA6000000); strokeRect(c, 20, 20, bw, 34, 17, 0x59FFFFFF, 1); }
            drawCentered(c, "LYRICS", 20, 20, bw, 34, 14, hanken600, lyricsOn ? DARK_TEXT : LIGHT);
            hit(20, 20, bw, 34, () -> { lyricsOn = !lyricsOn; invalidate(); });
        }

        // "3 / 12 · Scan/03.jpg" for 3 s after each change
        if (n > 1 && now - vChangedAt < 3000) {
            String lab = viewer.label(vIdx);
            String cnt = (vIdx + 1) + " / " + n + (lab != null ? "  ·  " + lab : "");
            float cw = Math.min(measure(cnt, 14, mono400) + 24, vw - 40);
            roundRect(c, vw - 20 - cw, 20, cw, 30, 15, 0xA6000000);
            text(c, cnt, vw - 20 - cw + 12, 40, 14, mono400, 0xD9FFFFFF, cw - 24);
        }

        // 1.0.12: page dots, so it is obvious there is more to swipe (Michael 2026-11-20); a counter past 12 pictures
        if (n > 1) {
            float dy = 418;
            if (n <= 12) {
                float gap = 14, dx = vw / 2 - (n - 1) * gap / 2;
                p.reset(); p.setAntiAlias(true);
                for (int i = 0; i < n; i++) {
                    p.setColor(i == vIdx ? LIGHT : 0x4DFFFFFF);
                    c.drawCircle(X(dx + i * gap), Y(dy), (i == vIdx ? 4f : 3f) * s, p);
                }
            } else {
                drawCentered(c, (vIdx + 1) + " / " + n, 0, dy - 9, vw, 18, 13, mono400, 0xB3FFFFFF);
            }
        }

        // reduced stats line. 1.0.12: Quality first (Michael: "a mini version of Quality"), then badge, format,
        // channels and time. No progress bar on purpose: the time already says where you are.
        if (sn == null || sn.track == null) return;
        Track t = sn.track;
        // badge first, then Quality (Michael 2026-11-20: "show Bit-perfect first, then quality")
        float y = 434, lx = 20;
        if (t.badge != Track.Badge.NONE) {
            boolean checking = t.badge == Track.Badge.CHECKING;
            lx += drawBadge(c, t.badgeText, badgeColor(t.badge), lx, y, 15, checking) + 12;
        }
        String q = quality(t);
        if (q != null) {
            text(c, q, lx, y + 21, 16, hanken600, ACCENT, 140);
            lx += Math.min(140, measure(q, 16, hanken600)) + 12;
        }
        String fmt = t.formatShort == null ? "" : t.formatShort;
        if (t.channels != null && t.channels >= 1) fmt = (fmt.isEmpty() ? "" : fmt + " · ") + Math.round(t.channels) + " ch";
        if (!fmt.isEmpty()) fmt += "   ";
        String tm = t.durationMs > 0 ? mss(position(sn)) + " / " + mss(t.durationMs) : t.state == 3 ? "" : "Paused";
        String left = fmt + tm;
        text(c, left, lx, y + 21, 16, mono400, 0xD9FFFFFF, 400);
        lx += Math.min(400, measure(left, 16, mono400)) + 24;
        String ta = (t.title == null ? "" : t.title) + (t.artist == null || t.artist.isEmpty() ? "" : "  ·  " + t.artist);
        float maxW = vw - 20 - lx;
        float tw = Math.min(maxW, measure(ta, 17, hanken500));
        if (maxW > 40) text(c, ta, vw - 20 - tw, y + 21, 17, hanken500, LIGHT, maxW);
    }

    /**
     * 1.0.12: lyrics, large, for reading across the room. Synced: the current line big and bright with two lines
     * before and three after, dimmer. Unsynced (no timestamps): the text scrolls with the track's progress.
     */
    private void drawLyrics(Canvas c, Poller.Snapshot sn, float top, float bottom) {
        Track t = sn.track;
        long pos = position(sn);
        float w = vw - 140, cx = 70;
        if (lyrics.synced) {
            int cur = lyrics.indexAt(pos);
            float mid = (top + bottom) / 2;
            // current line (wrapped, up to 2 rows), centred on mid
            String curText = cur >= 0 ? lyrics.lines.get(cur).text : "";
            List<String> rows = wrap(curText.isEmpty() ? "♪" : curText, 34, hanken600, w);
            if (rows.size() > 2) rows = rows.subList(0, 2);
            float ch = rows.size() * 42;
            float y = mid - ch / 2;
            for (String r : rows) { drawCentered(c, r, cx, y, w, 42, 34, hanken600, Color.WHITE); y += 42; }
            // before
            float by = mid - ch / 2 - 8;
            for (int i = cur - 1; i >= Math.max(0, cur - 2); i--) {
                by -= 32;
                if (by < top) break;
                drawCentered(c, lyrics.lines.get(i).text, cx, by, w, 32, 22, hanken500, 0x73FFFFFF);
            }
            // after
            float ay = mid + ch / 2 + 8;
            for (int i = cur + 1; i <= Math.min(lyrics.lines.size() - 1, cur + 3); i++) {
                if (ay + 32 > bottom) break;
                drawCentered(c, lyrics.lines.get(i).text, cx, ay, w, 32, 22, hanken500, i == cur + 1 ? 0xB3FFFFFF : 0x73FFFFFF);
                ay += 32;
            }
        } else {
            float frac = t.durationMs > 0 ? Math.min(1f, pos / (float) t.durationMs) : 0f;
            int n = lyrics.lines.size(), rows = (int) ((bottom - top) / 34);
            int first = Math.max(0, Math.min(n - rows, Math.round(frac * n) - rows / 2));
            float y = top;
            for (int i = first; i < n && y + 34 <= bottom; i++, y += 34) {
                drawCentered(c, lyrics.lines.get(i).text, cx, y, w, 34, 24, hanken500, 0xD9FFFFFF);
            }
        }
    }

    private void drawToggle(Canvas c, float x, float y) {
        float h = NAV_H, segW = (TOG_W - 8) / 2;
        roundRect(c, x, y, TOG_W, h, h / 2, 0x1AFFFFFF);
        // 1.0.10: on Settings & about neither side is selected; Detail then goes back to page 1
        boolean settings = detail && page == FEEDBACK_PAGE;
        if (!settings) {
            float ax = detail ? x + 4 + segW : x + 4;
            roundRect(c, ax, y + 4, segW, h - 8, (h - 8) / 2, LIGHT);
        }
        drawCentered(c, "Simple", x + 4, y + 4, segW, h - 8, 17, hanken600, detail || settings ? 0xBFFFFFFF : DARK_TEXT);
        drawCentered(c, "Detail", x + 4 + segW, y + 4, segW, h - 8, 17, hanken600, detail && !settings ? DARK_TEXT : 0xBFFFFFFF);
        hit(x, y, TOG_W / 2, h, () -> { if (detail) { if (page == FEEDBACK_PAGE) page = pageBeforeSettings; actions.toggleMode(); } });
        hit(x + TOG_W / 2, y, TOG_W / 2, h, () -> { if (!detail) actions.toggleMode(); else if (page == FEEDBACK_PAGE) leaveSettings(); });
    }

    private void drawVu(Canvas c, float x, float y, float w) {
        strokeRect(c, x + 0.75f, y + 0.75f, w - 1.5f, NAV_H - 1.5f, 10, 0x59FFFFFF, 1.5f);
        drawCentered(c, "VU", x, y, w, NAV_H, 18, mono500, Color.WHITE);
        hit(x, y, w, NAV_H, actions::vu);
    }

    /** 1.0.10: gear button, opens Settings & about. Lit while that page is showing. */
    private void drawGear(Canvas c, float x, float y, float w, boolean here) {
        int col = here ? ACCENT : Color.WHITE;
        if (here) roundRect(c, x, y, w, NAV_H, 10, 0x24D9B48A);
        strokeRect(c, x + 0.75f, y + 0.75f, w - 1.5f, NAV_H - 1.5f, 10, here ? ACCENT : 0x59FFFFFF, 1.5f);
        float cx = x + w / 2, cy = y + NAV_H / 2;
        p.reset(); p.setAntiAlias(true); p.setColor(col);
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(3.6f * s);
        for (int i = 0; i < 8; i++) {                 // teeth
            double a = i * Math.PI / 4;
            float ix = (float) Math.cos(a), iy = (float) Math.sin(a);
            c.drawLine(X(cx + ix * 9.5f), Y(cy + iy * 9.5f), X(cx + ix * 12.5f), Y(cy + iy * 12.5f), p);
        }
        p.setStrokeWidth(3f * s);
        c.drawCircle(X(cx), Y(cy), 7.5f * s, p);       // ring
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(X(cx), Y(cy), 2.2f * s, p);       // hub
        p.setStrokeCap(Paint.Cap.BUTT);
        hit(x, y, w, NAV_H, actions::openSettings);
    }

    /** 1.0.6: opens the Eversolo's own EQ app (on/off per output lives there). Lit like the "EQ active" badge when on. */
    private void drawEq(Canvas c, float x, float y, float w, boolean active) {
        int col = badgeColor(Track.Badge.EQ_ACTIVE);
        if (active) roundRect(c, x, y, w, NAV_H, 10, (col & 0x00FFFFFF) | 0x26000000);   // same tint as the badge
        strokeRect(c, x + 0.75f, y + 0.75f, w - 1.5f, NAV_H - 1.5f, 10, active ? col : 0x59FFFFFF, 1.5f);
        drawCentered(c, "EQ", x, y, w, NAV_H, 18, mono500, active ? col : Color.WHITE);
        hit(x, y, w, NAV_H, actions::eq);
    }

    private void drawPill(Canvas c, String text, float x, float y, float size, Typeface tf, int bg, int fg, float padX, float tracking) {
        tp.setTypeface(tf); tp.setTextSize(size * s); tp.setLetterSpacing(tracking);
        float tw = tp.measureText(text) / s, h = size + 12.5f;
        roundRect(c, x, y + 3, tw + 2 * padX, h, h / 2, bg);
        tp.setColor(fg);
        c.drawText(text, X(x + padX), Y(y + 3 + h / 2 + size * 0.35f), tp);
        tp.setLetterSpacing(0);
    }

    private void drawRoundButton(Canvas c, float x, float y, float d, boolean primary, String icon) {
        p.reset(); p.setAntiAlias(true);
        if (primary) { p.setColor(LIGHT); c.drawCircle(X(x + d / 2), Y(y + d / 2), d / 2 * s, p); }
        p.setColor(primary ? DARK_TEXT : Color.WHITE);
        float cx = x + d / 2, cy = y + d / 2, k = d >= 44 ? 1f : 0.75f;
        Path path = new Path();
        switch (icon) {
            case "play":
                path.moveTo(X(cx - 6 * k), Y(cy - 9 * k)); path.lineTo(X(cx + 9 * k), Y(cy)); path.lineTo(X(cx - 6 * k), Y(cy + 9 * k)); path.close();
                c.drawPath(path, p); break;
            case "pause":
                c.drawRect(X(cx - 7 * k), Y(cy - 8 * k), X(cx - 2 * k), Y(cy + 8 * k), p);
                c.drawRect(X(cx + 2 * k), Y(cy - 8 * k), X(cx + 7 * k), Y(cy + 8 * k), p); break;
            case "next":
                path.moveTo(X(cx - 7), Y(cy - 8)); path.lineTo(X(cx + 5), Y(cy)); path.lineTo(X(cx - 7), Y(cy + 8)); path.close();
                c.drawPath(path, p); c.drawRect(X(cx + 5), Y(cy - 8), X(cx + 8), Y(cy + 8), p); break;
            default:
                path.moveTo(X(cx + 7), Y(cy - 8)); path.lineTo(X(cx - 5), Y(cy)); path.lineTo(X(cx + 7), Y(cy + 8)); path.close();
                c.drawPath(path, p); c.drawRect(X(cx - 8), Y(cy - 8), X(cx - 5), Y(cy + 8), p);
        }
    }

    private void drawLock(Canvas c, float x, float y, int col) {
        p.reset(); p.setAntiAlias(true); p.setColor(col); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f * s);
        c.drawArc(new RectF(X(x + 2.5f), Y(y), X(x + 9.5f), Y(y + 9)), 180, 180, false, p);
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(X(x), Y(y + 5), X(x + 12), Y(y + 13)), 1.5f * s, 1.5f * s, p);
    }

    private void drawArrow(Canvas c, float x, float y, int col) {
        p.reset(); p.setAntiAlias(true); p.setColor(col); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * s);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(X(x), Y(y), X(x + 13), Y(y), p);
        c.drawLine(X(x + 9), Y(y - 4), X(x + 13), Y(y), p);
        c.drawLine(X(x + 9), Y(y + 4), X(x + 13), Y(y), p);
    }

    private void drawToast(Canvas c) {
        tp.setTypeface(hanken600); tp.setTextSize(17 * s);
        float tw = tp.measureText(toast) / s;
        float w = Math.min(vw - 40, tw + 36), x = (vw - w) / 2f, y = 412;
        roundRect(c, x, y, w, 42, 12, LIGHT);
        tp.setColor(DARK_TEXT);
        c.drawText(TextUtils.ellipsize(toast, tp, (w - 36) * s, TextUtils.TruncateAt.END).toString(), X(x + 18), Y(y + 27), tp);
    }

    /** A tracked capitals label that shrinks (to 75%) before it would run past maxW. */
    private void fitLabel(Canvas c, String text, float x, float baseline, float size, float maxW) {
        float sz = size;
        tp.setTypeface(hanken600); tp.setLetterSpacing(0.14f);
        tp.setTextSize(sz * s);
        while (tp.measureText(text) > maxW * s && sz > size * 0.75f) { sz -= 0.5f; tp.setTextSize(sz * s); }
        tp.setLetterSpacing(0);
        label(c, text, x, baseline, sz);
    }

    // ================================================================ Feedback page (1.0.3)

    /** Full screen in pixels (getRealMetrics: the whole panel, not just our window) plus density. */
    private String screenSize() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        android.view.Display d = getDisplay();
        if (d != null) d.getRealMetrics(dm); else dm = getResources().getDisplayMetrics();
        return FeedbackLink.sizeText(dm.widthPixels, dm.heightPixels) + ", " + dm.densityDpi + " dpi";
    }

    /** The part of the screen the player gives the app (it keeps its own sidebar). */
    private String appArea() { return getWidth() > 0 ? FeedbackLink.sizeText(getWidth(), getHeight()) : null; }

    private void drawFeedback(Canvas c, Poller.Snapshot sn) {
        maxScroll = 0;
        Map<String, String> m = sn.modelRaw;
        String model = v(m, "model"), dis = v(m, "disModel"), fw = v(m, "firmware");
        String choice = FeedbackLink.modelChoice(dis, model);
        String screen = screenSize();
        String details = FeedbackLink.details(BuildConfig.VERSION_NAME, model, dis, fw, v(m, "androidversion"), screen, appArea(), downloadDiag);
        String url = FeedbackLink.url(choice, fw, details);

        // 1.0.10: Settings & about, rebuilt from Michael's design handoff (2026-11-18): two panels, no scrolling.
        // Left "EverSignal": version + Check for updates / Release notes, Stay in front switch, today's stats Reset.
        // Right "Feedback": title, what the code fills in, privacy line pinned at the bottom, QR vertically centred (210 in 1.0.10, 250 from 1.0.11).
        long now = System.currentTimeMillis();
        float top = 70, bottom = 444, ph = bottom - top;
        float rw = 492, rx = vw - 14 - rw, lx0 = 16, lw = rx - 8 - lx0;

        // ---------------- left panel
        panel(c, "EverSignal", lx0, top, lw, ph);
        float ix = lx0 + 14, iw = lw - 28, y = top + 27 + 10;
        text(c, "Version", ix, y + 15, 17, hanken600, LIGHT, iw / 2);
        String ver = BuildConfig.VERSION_NAME;
        float vwid = measure(ver, 17, mono500);
        text(c, ver, ix + iw - vwid, y + 15, 17, mono500, LIGHT, vwid + 2);
        y += 20 + 9;
        float bw = (iw - 8) / 2;
        String cl = checkLabel != null && now < checkLabelUntil ? checkLabel : "Check for updates";
        if (checkLabel != null && now < checkLabelUntil) postInvalidateDelayed(200);
        outlineButton(c, cl, ix, y, bw, 44, 0x38FFFFFF, LIGHT, actions::checkUpdates);
        outlineButton(c, "Release notes", ix + bw + 8, y, bw, 44, 0x38FFFFFF, LIGHT, this::openAllNotes);
        y += 44 + 12;
        divider(c, ix, y, iw);

        // Stay in front: the whole row is tappable
        float rowTop = y, swW = 48, swH = 28, tw = iw - swW - 14;
        y += 12;
        text(c, "Stay in front", ix, y + 15, 17, hanken600, LIGHT, tw);
        float dy = y + 20 + 3;
        for (String line : wrap("Comes back a moment after you start music from the Eversolo phone app or AirPlay.", 13.5f, hanken400, tw)) {
            text(c, line, ix, dy + 14, 13.5f, hanken400, 0x99FFFFFF, tw + 4);
            dy += 19;
        }
        float rowBottom = dy + 12;
        float swX = ix + iw - swW, swY = (rowTop + rowBottom) / 2 - swH / 2;
        roundRect(c, swX, swY, swW, swH, swH / 2, stayOn ? CHIP_GREEN : 0x33FFFFFF);
        p.reset(); p.setAntiAlias(true); p.setColor(LIGHT);
        c.drawCircle(X(stayOn ? swX + swW - 3 - 11 : swX + 3 + 11), Y(swY + swH / 2), 11 * s, p);
        // 1.0.11: only the switch (plus a margin, 44 tall minimum) toggles. The whole row used to, and Michael kept
        // turning it off by accident while tapping near it (2026-11-19).
        hit(swX - 12, swY + swH / 2 - 22, swW + 24, 44, actions::toggleStay);
        y = rowBottom;
        divider(c, ix, y, iw);

        // 1.0.12: Frame covers, same switch as Stay in front (the two switches sit together)
        rowTop = y; y += 12;
        tw = iw - swW - 14;
        text(c, "Frame covers", ix, y + 15, 17, hanken600, LIGHT, tw);
        dy = y + 20 + 3;
        for (String line : wrap("A thin border in a colour from the album art.", 13.5f, hanken400, tw)) {
            text(c, line, ix, dy + 14, 13.5f, hanken400, 0x99FFFFFF, tw + 4);
            dy += 19;
        }
        rowBottom = dy + 12;
        swY = (rowTop + rowBottom) / 2 - swH / 2;
        roundRect(c, swX, swY, swW, swH, swH / 2, frame ? CHIP_GREEN : 0x33FFFFFF);
        p.reset(); p.setAntiAlias(true); p.setColor(LIGHT);
        c.drawCircle(X(frame ? swX + swW - 3 - 11 : swX + 3 + 11), Y(swY + swH / 2), 11 * s, p);
        hit(swX - 12, swY + swH / 2 - 22, swW + 24, 44, actions::toggleFrame);
        y = rowBottom;
        divider(c, ix, y, iw);

        // Today's listening stats: Reset, two steps. 1.0.12: moved to the bottom (Michael: "cleaner")
        boolean armed = now < resetArmedUntil, done = now < resetDoneUntil;
        if (armed || done) postInvalidateDelayed(200);
        String rl = done ? "Cleared" : armed ? "Tap to confirm" : "Reset";
        float rbw = measure(rl, 15, hanken600) + 32;
        rowTop = y; y += 12;
        tw = iw - rbw - 14;
        text(c, "Today's listening stats", ix, y + 15, 17, hanken600, LIGHT, tw);
        dy = y + 20 + 3;
        for (String line : wrap("Clears the Session panel on the Detail screen.", 13.5f, hanken400, tw)) {
            text(c, line, ix, dy + 14, 13.5f, hanken400, 0x99FFFFFF, tw + 4);
            dy += 19;
        }
        rowBottom = dy + 12;
        int rc = armed ? ORANGE : done ? GREEN : LIGHT;
        outlineButton(c, rl, ix + iw - rbw, (rowTop + rowBottom) / 2 - 22, rbw, 44, armed ? ORANGE : 0x38FFFFFF, rc, this::resetTapped);

        // ---------------- right panel
        panel(c, "Feedback", rx, top, rw, ph);
        float qs = 250, qx = rx + rw - 14 - qs, qy = top + (ph - qs) / 2;
        float fx = rx + 14, fcw = qx - 16 - fx, fy = top + 27 + 2;
        for (String line : wrap("Found a bug, or got it working on your model?", 18, hanken600, fcw)) {
            text(c, line, fx, fy + 17, 18, hanken600, LIGHT, fcw + 4);
            fy += 22;
        }
        fy += 6;
        for (String line : wrap("Scan the code to open the form. These details are filled in for you:", 13, hanken400, fcw)) {
            text(c, line, fx, fy + 13, 13, hanken400, 0x99FFFFFF, fcw + 4);
            fy += 18;
        }
        fy += 12;
        String modelV = choice != null ? choice : dis != null ? dis : model != null ? model : null;
        String[][] rows = {
            {"Model", modelV},
            {"Firmware", fw != null ? fw : "N/A"},
            {"EverSignal", BuildConfig.VERSION_NAME},
            {"Screen", screen},
        };
        float kw = 0;
        for (String[] r : rows) kw = Math.max(kw, measure(r[0], 12, hanken400));
        for (String[] r : rows) {
            text(c, r[0], fx, fy + 12, 12, hanken400, 0x80FFFFFF, kw + 2);
            float vx = fx + kw + 12, vmax = fcw - kw - 12;
            if (r == rows[0] && choice == null) {
                String v = r[1] != null ? r[1] + " " : "";
                text(c, v, vx, fy + 12, 12, mono400, LIGHT, vmax);
                text(c, "(pick yours)", vx + measure(v, 12, mono400), fy + 12, 12, mono400, 0x73FFFFFF, Math.max(0, vmax - measure(v, 12, mono400)));
            } else {
                text(c, r[1], vx, fy + 12, 12, mono400, LIGHT, vmax);
            }
            fy += 21;
        }
        java.util.List<String> priv = wrap("EverSignal sends nothing. Your phone opens the form only if you scan. No account needed.", 11, hanken400, fcw);
        float py = bottom - 12 - priv.size() * 16;
        for (String line : priv) { text(c, line, fx, py + 11, 11, hanken400, 0x80FFFFFF, fcw + 4); py += 16; }

        roundRect(c, qx, qy, qs, qs, 12, Color.WHITE);
        Bitmap b = qrBitmap(url);
        if (b != null) {
            // 1.0.11 (Q2): every module the same whole number of screen pixels, centred in the white box.
            // 1.0.10 stretched the bitmap to a fractional size, so blocks came out unevenly 2 and 3 pixels wide.
            float pad = 10;
            float innerPx = (qs - 2 * pad) * s;
            int mod = Math.max(1, (int) Math.floor(innerPx / b.getWidth()));
            float sizePx = mod * b.getWidth();
            float left = (float) Math.floor(X(qx + qs / 2f) - sizePx / 2f), topPx = (float) Math.floor(Y(qy + qs / 2f) - sizePx / 2f);
            Paint qp = new Paint();
            qp.setFilterBitmap(false); qp.setAntiAlias(false);
            c.drawBitmap(b, null, new RectF(left, topPx, left + sizePx, topPx + sizePx), qp);
        } else {
            drawCentered(c, "QR code unavailable", qx, qy, qs, qs, 13, hanken500, DARK_TEXT);
        }
    }

    /** Outlined button (design: radius 8, 1.5 border), label centred; at least 44 tall for the hit target. */
    private void outlineButton(Canvas c, String label, float x, float y, float w, float h, int border, int textCol, Runnable r) {
        strokeRect(c, x + 0.75f, y + 0.75f, w - 1.5f, h - 1.5f, 8, border, 1.5f);
        drawCentered(c, label, x, y, w, h, 15, hanken600, textCol);
        hit(x, y, w, h, r);
    }

    /** The QR code as a 1-pixel-per-module bitmap, drawn scaled up without smoothing. Rebuilt only when the link changes. */
    private Bitmap qrBitmap(String url) {
        if (url.equals(qrFor)) return qr;
        qrFor = url;
        qr = null;
        try {
            Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 0);
            hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L);
            com.google.zxing.common.BitMatrix mx = new com.google.zxing.qrcode.QRCodeWriter()
                .encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = mx.getWidth(), h = mx.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = mx.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            qr = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (com.google.zxing.WriterException e) {
            qr = null;
        }
        return qr;
    }

    private void label(Canvas c, String text, float x, float baseline, float size) {
        tp.setTypeface(hanken600); tp.setTextSize(size * s); tp.setColor(ACCENT); tp.setLetterSpacing(0.14f);
        c.drawText(text, X(x), Y(baseline), tp);
        tp.setLetterSpacing(0);
    }

    private void text(Canvas c, String text, float x, float baseline, float size, Typeface tf, int col, float maxW) {
        if (text == null) return;
        tp.setTypeface(tf); tp.setTextSize(size * s); tp.setColor(col);
        c.drawText(TextUtils.ellipsize(text, tp, maxW * s, TextUtils.TruncateAt.END).toString(), X(x), Y(baseline), tp);
    }

    /** Shrinks the text until it fits: sample rates and bit depths must never be cut off. */
    private void fitText(Canvas c, String text, float x, float baseline, float size, Typeface tf, int col, float maxW) {
        if (text == null || text.isEmpty()) return;
        tp.setTypeface(tf); tp.setColor(col);
        float sz = size;
        tp.setTextSize(sz * s);
        while (tp.measureText(text) > maxW * s && sz > size * 0.65f) { sz -= 0.5f; tp.setTextSize(sz * s); }
        c.drawText(TextUtils.ellipsize(text, tp, maxW * s, TextUtils.TruncateAt.END).toString(), X(x), Y(baseline), tp);
    }

    private void drawCentered(Canvas c, String text, float x, float y, float w, float h, float size, Typeface tf, int col) {
        tp.setTypeface(tf); tp.setTextSize(size * s); tp.setColor(col);
        float tw = tp.measureText(text);
        Paint.FontMetrics fm = tp.getFontMetrics();
        c.drawText(text, X(x) + (w * s - tw) / 2f, Y(y) + (h * s - (fm.descent + fm.ascent)) / 2f, tp);
    }

    /** Word-wraps one line to maxW; continuation lines repeat the line's leading indent. */
    private java.util.List<String> wrap(String line, float size, Typeface tf, float maxW) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        String indent = line.substring(0, n), cur = indent;
        for (String word : line.substring(n).split(" ")) {
            String next = cur.equals(indent) ? cur + word : cur + " " + word;
            if (!cur.equals(indent) && measure(next, size, tf) > maxW) { out.add(cur); cur = indent + word; }
            else cur = next;
        }
        out.add(cur);
        return out;
    }

    private float measure(String text, float size, Typeface tf) {
        if (text == null) return 0;
        tp.setTypeface(tf); tp.setTextSize(size * s);
        return tp.measureText(text) / s;
    }

    private void roundRect(Canvas c, float x, float y, float w, float h, float r, int col) {
        p.reset(); p.setAntiAlias(true); p.setColor(col);
        c.drawRoundRect(new RectF(X(x), Y(y), X(x + w), Y(y + h)), r * s, r * s, p);
    }

    private void strokeRect(Canvas c, float x, float y, float w, float h, float r, int col, float sw) {
        p.reset(); p.setAntiAlias(true); p.setColor(col); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw * s);
        c.drawRoundRect(new RectF(X(x), Y(y), X(x + w), Y(y + h)), r * s, r * s, p);
    }

    private void hit(float x, float y, float w, float h, Runnable r) {
        RectF rect = new RectF(X(x), Y(y), X(x + w), Y(y + h));
        if (hitClip != null) {          // scrolled content: only what is on screen can be tapped
            if (!rect.intersect(hitClip)) return;
        }
        hits.add(new Object[]{rect, r});
    }

    // ================================================================ Detail zoom (1.0.2: readable at the rack)

    /**
     * Detail content is drawn 1.4x larger than the design canvas (most text 11.5 -> ~16), in a narrower virtual
     * area that scrolls. Everything inside uses the normal X()/Y()/measure() helpers; beginContent swaps the scale.
     */
    static final float Z = 1.8f;
    private static final float TOP = 70, BOTTOM = 462;
    private RectF hitClip;
    private float savedS, savedOx, savedOy;

    private float contentW() { return (vw - 30) / Z; }
    /** Columns on the stats pages: 2 on the A6, 3 once the canvas is wide enough (T10). */
    private int columns() { return vw >= 1150 ? 3 : 2; }
    private static float contentH() { return (BOTTOM - TOP) / Z; }

    private void beginContent(Canvas c) {
        c.save();
        c.clipRect(X(16), Y(TOP), X(vw - 14), Y(BOTTOM));
        hitClip = new RectF(X(16), Y(TOP), X(vw - 14), Y(BOTTOM));
        savedS = s; savedOx = ox; savedOy = oy;
        float x0 = X(16), y0 = Y(TOP);
        s = savedS * Z;
        ox = x0;
        oy = y0 - scroll[page] * s;
    }

    /** Restores the normal scale, then sets the scroll range from the content height and draws the scrollbar. */
    private void endContent(Canvas c, float content) {
        s = savedS; ox = savedOx; oy = savedOy;
        hitClip = null;
        c.restore();
        maxScroll = Math.max(0, content - contentH());
        if (scroll[page] > maxScroll) scroll[page] = maxScroll;
        if (maxScroll > 0) {
            float track = BOTTOM - TOP, barH = Math.max(24, track * contentH() / content);
            float barY = TOP + (track - barH) * (scroll[page] / maxScroll);
            roundRect(c, vw - 11, barY, 3, barH, 1.5f, 0x40FFFFFF);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); downScroll = notes != null ? notesScroll : scroll[page]; dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (viewer != null) return true;
                if (notes != null) {
                    if (Math.abs(dy) > 8 * s) {
                        dragging = true;
                        notesScroll = Math.max(0, Math.min(notesMax, downScroll - dy / s));
                        invalidate();
                    }
                    return true;
                }
                if (info == null && detail && Math.abs(dy) > 8 * s && Math.abs(dy) > Math.abs(dx)) {
                    dragging = true;
                    scroll[page] = Math.max(0, Math.min(maxScroll, downScroll - dy / (s * Z)));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (viewer != null) {   // 1.0.12: swipe steps, a button acts, any other tap closes
                    if (Math.abs(dx) > 60 * s && Math.abs(dx) > Math.abs(dy) * 1.5f) { viewerStep(dx < 0 ? 1 : -1); return true; }
                    for (int i = hits.size() - 1; i >= 0; i--) {
                        RectF r = (RectF) hits.get(i)[0];
                        RectF big = new RectF(r.left - 6 * s, r.top - 6 * s, r.right + 6 * s, r.bottom + 6 * s);
                        if (big.contains(e.getX(), e.getY())) { ((Runnable) hits.get(i)[1]).run(); performClick(); return true; }
                    }
                    closeViewer(); performClick();
                    return true;
                }
                if (dragging) return true;
                if (notes == null && info == null && detail && Math.abs(dx) > 60 * s && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    page = page == FEEDBACK_PAGE ? (dx < 0 ? 0 : CAROUSEL - 1) : (page + (dx < 0 ? 1 : CAROUSEL - 1)) % CAROUSEL;
                    invalidate();
                    return true;
                }
                for (int i = hits.size() - 1; i >= 0; i--) {
                    RectF r = (RectF) hits.get(i)[0];
                    RectF big = new RectF(r.left - 6 * s, r.top - 6 * s, r.right + 6 * s, r.bottom + 6 * s);
                    if (big.contains(e.getX(), e.getY())) { ((Runnable) hits.get(i)[1]).run(); performClick(); return true; }
                }
                return true;
            }
            default: return true;
        }
    }

    /**
     * 1.0.4: mouse wheel (the A6's PC control in a browser, or a USB mouse). Touch drags never covered it:
     * a wheel arrives as ACTION_SCROLL, not as a touch. Vertical scrolls the Detail page; horizontal
     * (Shift + wheel, or a trackpad swipe) changes page, like a swipe.
     */
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_SCROLL && viewer != null) {   // 1.0.12: wheel steps the viewer
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL), h = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            float d = h != 0 ? h : -v;
            if (d != 0) viewerStep(d > 0 ? 1 : -1);
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_SCROLL && notes != null) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v != 0) { notesScroll = Math.max(0, Math.min(notesMax, notesScroll - v * 50)); invalidate(); }
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_SCROLL && detail && info == null) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL), h = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (h != 0) {
                page = page == FEEDBACK_PAGE ? (h > 0 ? 0 : CAROUSEL - 1) : (page + (h > 0 ? 1 : CAROUSEL - 1)) % CAROUSEL;
                invalidate();
                return true;
            }
            if (v != 0) {
                scroll[page] = Math.max(0, Math.min(maxScroll, scroll[page] - v * WHEEL_STEP));
                invalidate();
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    /** Virtual (zoomed) units per wheel notch: about two rows of stats. */
    private static final float WHEEL_STEP = 36;

    @Override public boolean performClick() { return super.performClick(); }
}
