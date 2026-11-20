package com.malbers.eversignal;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.0.12: the pictures behind the full-screen cover viewer. Decodes on its own thread and keeps only the shown
 * picture and its neighbours in memory (a booklet can be 40 large scans).
 */
final class ArtShow {
    final String folder;               // the album folder these pictures came from (null = the player's cover only)
    private final List<ArtLoader.Item> items;
    private final Bitmap single;       // no readable folder: just the cover the player gave us
    private final Map<Integer, Bitmap> cache = new HashMap<>();
    private final Set<Integer> loading = new HashSet<>();
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final Runnable onLoaded;
    private final int maxDim;
    private volatile boolean closed;

    ArtShow(String folder, List<ArtLoader.Item> items, Bitmap fallback, int maxDim, Runnable onLoaded) {
        this.folder = folder;
        this.items = items == null ? new ArrayList<>() : items;
        this.single = this.items.isEmpty() ? fallback : null;
        this.maxDim = maxDim;
        this.onLoaded = onLoaded;
    }

    int size() { return items.isEmpty() ? (single != null ? 1 : 0) : items.size(); }

    String label(int i) { return items.isEmpty() ? null : items.get(i).label; }

    /** The picture at i, or null while it decodes (or when it can't be decoded). Prefetches the next one. */
    synchronized Bitmap get(int i) {
        if (items.isEmpty()) return single;
        if (i < 0 || i >= items.size()) return null;
        load(i);
        load((i + 1) % items.size());
        return cache.get(i);
    }

    /** True once a picture has been tried and could not be decoded: the slideshow skips it. */
    synchronized boolean failed(int i) { return !items.isEmpty() && failed.contains(i); }
    private final Set<Integer> failed = new HashSet<>();

    private void load(int i) {
        if (cache.containsKey(i) || loading.contains(i) || failed.contains(i) || closed) return;
        loading.add(i);
        ex.execute(() -> {
            Bitmap b = ArtLoader.decode(items.get(i), maxDim);
            synchronized (ArtShow.this) {
                loading.remove(i);
                if (closed) return;
                if (b == null) failed.add(i);
                else cache.put(i, b);
            }
            onLoaded.run();
        });
    }

    /** Drop everything but i and its neighbours. */
    synchronized void trim(int i) {
        int n = items.size();
        if (n == 0) return;
        cache.keySet().removeIf(k -> k != i && k != (i + 1) % n && k != (i - 1 + n) % n);
    }

    synchronized void close() { closed = true; cache.clear(); ex.shutdownNow(); }
}
