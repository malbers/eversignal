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
        void toggleStay();
        void openChannel();
        void update();
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
    private static final float NAV_H = 44, TOG_W = 200, VU_W = 72;

    private final Typeface hanken400, hanken500, hanken600, mono400, mono500;
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
    private long infoUntil;
    private float s = 1, ox, oy;
    /**
     * Virtual canvas width (1.0.4). Height is always 480; width is 900 on the A6 and grows on wider screens
     * (the T10's 1600 x 600 panel gives 1280), so layouts stretch instead of leaving bands at the sides.
     */
    private float vw = 900;

    static final String[] PAGES = {"Overview", "Track & file", "Queue & playback", "Device", "Inputs, outputs & volume", "Feedback"};
    /** 1.0.3: the last Detail page is a QR code to the feedback form (the A6 has no browser; a phone does). */
    static final int FEEDBACK_PAGE = 5;
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
    public void setStayOn(boolean on) { stayOn = on; invalidate(); }
    public void toast(String text) { toast = text; toastUntil = System.currentTimeMillis() + 1600; invalidate(); }

    /** The update pill: null hides it; colour says the state (offer / downloading / saved / failed). */
    private String update;
    private int updateColor = GREEN;
    public void setUpdate(String text, int color) { update = text; updateColor = color; invalidate(); }
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
        int tint = sn == null ? Color.rgb(42, 38, 34) : sn.tint;
        p.reset();
        p.setShader(new RadialGradient(X(vw - 120), Y(230), 0.62f * 1056 * s, tint, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        c.save();
        c.clipRect(X(0), Y(0), X(vw), Y(480));
        if (sn == null || sn.track == null) drawWaiting(c, sn);
        else if (detail) drawDetail(c, sn);
        else drawSimple(c, sn);
        c.restore();

        long now = System.currentTimeMillis();
        if (info != null && now < infoUntil) drawInfo(c);
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
        x -= 10 + VU_W;
        drawVu(c, x, NAV_Y, VU_W);
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

    private void drawSimple(Canvas c, Poller.Snapshot sn) {
        Track t = sn.track;
        // 1.0.2: the stats are the point, so the cover shrinks (340 -> 260) and the left side grows; no text below ~16
        final float left = 32, right = vw - 302, w = right - left;
        final float coverX = vw - 272, coverY = 92, coverS = 260;

        drawCover(c, sn.cover, coverX, coverY, coverS, 10, true);
        // credit under the cover, right-aligned with it; tap opens the YouTube channel
        String credit = "made by @malbers";
        float cw = measure(credit, 15, hanken500);
        text(c, credit, vw - 12 - cw, 448, 15, hanken500, 0x8CFFFFFF, cw + 2);
        hit(vw - 12 - cw - 8, 428, cw + 16, 28, actions::openChannel);
        // update pill above the credit, as wide as the cover
        drawUpdate(c, coverX, 392, coverS, false);
        drawNav(c, t);
        String chip = sourceChip(t);
        drawPill(c, chip.toUpperCase(Locale.US), left, NAV_Y, 14, hanken600, 0x1AFFFFFF, Color.WHITE, 12, 0.12f);

        // transport (bottom)
        float rowY = 414;
        float progY = 368;
        drawProgress(c, sn, left, progY, w, 17);
        float bx = right - 48;
        drawRoundButton(c, bx, rowY + 6, 48, false, "next"); hit(bx, rowY + 6, 48, 48, actions::next);
        bx -= 8 + 58;
        drawRoundButton(c, bx, rowY + 1, 58, true, t.state == 3 ? "pause" : "play"); hit(bx, rowY + 1, 58, 58, actions::playPause);
        bx -= 8 + 48;
        drawRoundButton(c, bx, rowY + 6, 48, false, "prev"); hit(bx, rowY + 6, 48, 48, actions::previous);
        if (t.hasQueue && sn.nextTitle != null) {
            label(c, "UP NEXT", left, rowY + 22, 13);
            fitText(c, sn.nextTitle, left, rowY + 48, 19, hanken500, 0xE0FFFFFF, bx - left - 16);
        }

        // badge (+ MQA if not already the badge) + note + format
        float badgeY = 326;
        if (t.badge != Track.Badge.NONE) {
            float bw = drawBadge(c, t.badgeText, badgeColor(t.badge), left, badgeY, 18);
            float nx = left + bw + 10;
            hit(left, badgeY, bw, 31, () -> showInfo(t));
            if (t.mqaOut && t.badge != Track.Badge.MQA) nx += drawBadge(c, "MQA", VIOLET, nx, badgeY, 18) + 10;
            String note = t.badgeNote == null ? "" : t.badgeNote;
            if (t.formatShort != null) note = note.isEmpty() ? t.formatShort : note + " · " + t.formatShort;
            // shrink before cutting: the format at the end must stay readable
            fitText(c, note, nx + 2, badgeY + 22, 17, mono400, 0xBFFFFFFF, right - nx - 2);
        }

        // stat tiles
        drawTiles(c, sn, left, 258, w, 60);
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
            {"PROCESSING", t.eq ? "EQ on" : "EQ off", t.dsp ? "DSP on" : "DSP off", t.sub ? "Sub on" : "Sub off"},
            {"OUTPUT", out, o1, ""},
            {"PORT", t.iface == null ? "…" : t.iface, t.iface == null ? "" : (t.analogPort() ? "analog out" : "digital out"), ""},
        };
    }

    /** Quality · Rate/depth · Bitrate (exact) · File size · In → Out. A tile the source doesn't report is left out. */
    private void drawTiles(Canvas c, Poller.Snapshot sn, float x, float y, float w, float h) {
        Track t = sn.track;
        List<String[]> tiles = new ArrayList<>();
        String quality = t.quality != null ? t.quality
            : t.dsd ? "DSD" : t.lossy ? "Lossy" : "hires".equals(t.formatClass()) ? "Hi-res" : t.rateHz == null ? null : "CD quality";
        // no source line: the top-left chip already names it
        if (quality != null) tiles.add(new String[]{"QUALITY", quality, ""});
        if (t.rateHz != null) tiles.add(new String[]{"RATE / DEPTH", Track.rateText(t.rateHz, t.dsd), t.bits == null ? "" : Math.round(t.bits) + "-bit"});
        if (sn.exactBps != null) tiles.add(new String[]{"BITRATE", String.format(Locale.US, "%,d", sn.exactBps.longValue()), "bps"});
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
            default: return ORANGE;
        }
    }

    /** Draws one badge pill; returns its width. */
    private float drawBadge(Canvas c, String text, int col, float x, float y, float size) {
        tp.setTypeface(hanken600);
        tp.setTextSize(size * s);
        float tw = tp.measureText(text) / s;
        float bw = 10 + 7 + 7 + tw + 12, bh = size + 13;
        roundRect(c, x, y, bw, bh, bh / 2, (col & 0x00FFFFFF) | 0x26000000);
        p.reset(); p.setAntiAlias(true); p.setColor(col);
        c.drawCircle(X(x + 10 + 3.5f), Y(y + bh / 2), 3.5f * s, p);
        tp.setColor(col);
        c.drawText(text, X(x + 10 + 7 + 7), Y(y + bh / 2 + size * 0.35f), tp);
        return bw;
    }

    private void showInfo(Track t) {
        // 1.0.5: explains the badge that was tapped, with a live "to get Bit-perfect" checklist (BadgeInfo)
        info = BadgeInfo.lines(t).toArray(new String[0]);
        infoUntil = System.currentTimeMillis() + 15000;
        invalidate();
    }

    private void drawInfo(Canvas c) {
        float w = 820, lh = 28, h = 30 + info.length * lh, x = (vw - w) / 2f, y = (480 - h) / 2f;
        p.reset(); p.setColor(0xB3000000);
        c.drawRect(X(0), Y(0), X(vw), Y(480), p);
        roundRect(c, x, y, w, h, 14, 0xFF1B1A19);
        strokeRect(c, x + 0.5f, y + 0.5f, w - 1, h - 1, 14, 0x24FFFFFF, 1);
        for (int i = 0; i < info.length; i++) {
            boolean head = i == 0, last = i == info.length - 1;
            fitText(c, info[i], x + 24, y + 32 + i * lh, head ? 19 : 17, head ? hanken600 : hanken400,
                last ? 0x99FFFFFF : head ? Color.WHITE : 0xD9FFFFFF, w - 48);
        }
        hits.add(new Object[]{new RectF(0, 0, getWidth(), getHeight()), (Runnable) () -> { info = null; invalidate(); }});
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
        fitText(c, t.title == null ? "" : t.title, hx + 62, hy + 21, 20, hanken600, Color.WHITE, px - hx - 62 - 12);
        float by = hy + 31, bx = hx + 62;
        if (t.badge != Track.Badge.NONE) {
            tp.setTypeface(hanken600); tp.setTextSize(14 * s);
            float tw = tp.measureText(t.badgeText) / s;
            int col = badgeColor(t.badge);
            float bw = 7 + 6 + 6 + tw + 9;
            roundRect(c, bx, by, bw, 21, 10.5f, (col & 0x00FFFFFF) | 0x26000000);
            p.reset(); p.setAntiAlias(true); p.setColor(col);
            c.drawCircle(X(bx + 7 + 3), Y(by + 10.5f), 3 * s, p);
            tp.setColor(col);
            c.drawText(t.badgeText, X(bx + 19), Y(by + 15.5f), tp);
            hit(bx, by, bw, 21, () -> showInfo(t));
            bx += bw + 10;
        }
        long pos = position(sn);
        float frac = t.durationMs > 0 ? Math.min(1f, pos / (float) t.durationMs) : 0;
        float barW = Math.max(50, Math.min(200, px - bx - 150));
        roundRect(c, bx, by + 9, barW, 3, 1.5f, 0x24FFFFFF);
        if (frac > 0) roundRect(c, bx, by + 9, barW * frac, 3, 1.5f, LIGHT);
        text(c, mss(pos) + " / " + (t.durationMs > 0 ? mss(t.durationMs) : "–"), bx + barW + 10, by + 15.5f, 14, mono400, 0xB3FFFFFF, 140);

        drawDots(c);
        if (page == FEEDBACK_PAGE) { drawFeedback(c, sn); return; }
        if (page > 0) { drawStatsPage(c, sn); return; }

        // Two columns, zoomed, scrolling. What you check at the rack comes first: Music + Source | Signal path;
        // then Session + Link | Device further down.
        beginContent(c);
        float musicH = 27 + 48 + (t.tags.isEmpty() ? 0 : 19) + 9 * 16.5f + 10, sourceH = 118, sessH = 268, linkH = 112, signalH = 388;
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
        ry = row1(c, "EQ", t.eq ? "On" : "Off", ix, ry, iw);
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
        float sy = ry;
        row1(c, "Stay in front", stayOn ? "On" : "Off", ix, sy, iw);
        hit(ix, sy - 3, iw, 22, actions::toggleStay);
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
        float d = 7, gap = 10, total = PAGES.length * d + (PAGES.length - 1) * gap;
        float x = (vw - total) / 2f, y = 469;
        for (int i = 0; i < PAGES.length; i++) {
            p.reset(); p.setAntiAlias(true); p.setColor(i == page ? LIGHT : 0x40FFFFFF);
            c.drawCircle(X(x + d / 2), Y(y + d / 2), d / 2 * s, p);
            final int target = i;
            hit(x - 4, y - 6, d + 8, d + 12, () -> { page = target; invalidate(); });
            x += d + gap;
        }
        text(c, (page + 1) + " / " + PAGES.length + "  " + PAGES[page], 16, y + 8, 13, hanken500, 0x99FFFFFF, 330);
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
        strokeRect(c, x + 0.5f, y + 0.5f, size - 1, size - 1, radius, 0x0FFFFFFF, 1);
    }

    private void drawToggle(Canvas c, float x, float y) {
        float h = NAV_H, segW = (TOG_W - 8) / 2;
        roundRect(c, x, y, TOG_W, h, h / 2, 0x1AFFFFFF);
        float ax = detail ? x + 4 + segW : x + 4;
        roundRect(c, ax, y + 4, segW, h - 8, (h - 8) / 2, LIGHT);
        drawCentered(c, "Simple", x + 4, y + 4, segW, h - 8, 18, hanken600, detail ? 0xBFFFFFFF : DARK_TEXT);
        drawCentered(c, "Detail", x + 4 + segW, y + 4, segW, h - 8, 18, hanken600, detail ? DARK_TEXT : 0xBFFFFFFF);
        hit(x, y, TOG_W, h, actions::toggleMode);
    }

    private void drawVu(Canvas c, float x, float y, float w) {
        strokeRect(c, x + 0.75f, y + 0.75f, w - 1.5f, NAV_H - 1.5f, 10, 0x59FFFFFF, 1.5f);
        drawCentered(c, "VU", x, y, w, NAV_H, 18, mono500, Color.WHITE);
        hit(x, y, w, NAV_H, actions::vu);
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
        String details = FeedbackLink.details(BuildConfig.VERSION_NAME, model, dis, fw, v(m, "androidversion"), screen, appArea());
        String url = FeedbackLink.url(choice, fw, details);

        float lx = 24, tw = 520;
        label(c, "FEEDBACK", lx, 104, 12);
        fitText(c, "Found a bug, or got it working on your model?", lx, 138, 24, hanken600, Color.WHITE, tw);
        text(c, "Scan the code with your phone to open the feedback form.", lx, 170, 17, hanken400, 0xD9FFFFFF, tw);
        text(c, "It fills these in for you:", lx, 212, 15, hanken500, 0xB3FFFFFF, tw);
        float ry = 240;
        String[][] rows = {
            {"Model", choice != null ? choice : (dis != null ? dis + " (pick yours)" : model != null ? model + " (pick yours)" : "you pick it")},
            {"Firmware", fw != null ? fw : "N/A"},
            {"EverSignal", BuildConfig.VERSION_NAME},
            {"Screen", screen},
        };
        for (String[] r : rows) {
            text(c, r[0], lx, ry, 15, hanken500, ACCENT, 120);
            text(c, r[1], lx + 120, ry, 15, mono400, Color.WHITE, tw - 120);
            ry += 26;
        }
        text(c, "EverSignal sends nothing. Your phone opens the form, and only if you scan.", lx, 370, 14, hanken400, 0x99FFFFFF, tw);
        text(c, "No account needed. Email is optional.", lx, 394, 14, hanken400, 0x99FFFFFF, tw);

        float box = 300, bx = vw - 14 - 16 - box, by = 96;
        roundRect(c, bx, by, box, box, 14, Color.WHITE);
        Bitmap b = qrBitmap(url);
        if (b != null) {
            float pad = 18;
            Paint qp = new Paint();
            qp.setFilterBitmap(false); qp.setAntiAlias(false);
            c.drawBitmap(b, null, new RectF(X(bx + pad), Y(by + pad), X(bx + box - pad), Y(by + box - pad)), qp);
        } else {
            drawCentered(c, "QR code unavailable", bx, by, box, box, 15, hanken500, DARK_TEXT);
        }
        drawCentered(c, "Scan with your phone camera", bx, by + box + 6, box, 24, 14, hanken500, 0xB3FFFFFF);
    }

    /** The QR code as a 1-pixel-per-module bitmap, drawn scaled up without smoothing. Rebuilt only when the link changes. */
    private Bitmap qrBitmap(String url) {
        if (url.equals(qrFor)) return qr;
        qrFor = url;
        qr = null;
        try {
            Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 0);
            hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
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
                downX = e.getX(); downY = e.getY(); downScroll = scroll[page]; dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (info == null && detail && Math.abs(dy) > 8 * s && Math.abs(dy) > Math.abs(dx)) {
                    dragging = true;
                    scroll[page] = Math.max(0, Math.min(maxScroll, downScroll - dy / (s * Z)));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (dragging) return true;
                if (info == null && detail && Math.abs(dx) > 60 * s && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    page = (page + (dx < 0 ? 1 : PAGES.length - 1)) % PAGES.length;
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
        if (e.getAction() == MotionEvent.ACTION_SCROLL && detail && info == null) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL), h = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (h != 0) {
                page = (page + (h > 0 ? 1 : PAGES.length - 1)) % PAGES.length;
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
