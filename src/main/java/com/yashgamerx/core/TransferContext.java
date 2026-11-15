package com.yashgamerx.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-transfer state shared by sender and receiver: configuration, statistics and
 * every socket/file opened, so a timed-out trial can be torn down from outside.
 */
public final class TransferContext {
    public final Config cfg;
    public final TransferStats stats;
    private final List<AutoCloseable> resources = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled;

    public TransferContext(Config cfg, TransferStats stats) {
        this.cfg = cfg;
        this.stats = stats;
    }

    public <T extends AutoCloseable> T track(T resource) {
        resources.add(resource);
        if (cancelled) closeQuietly(resource);
        return resource;
    }

    public boolean cancelled() {
        return cancelled;
    }

    /** Closes every tracked resource; blocked socket calls in other threads fail and unwind. */
    public void cancel() {
        cancelled = true;
        for (AutoCloseable r : resources) closeQuietly(r);
    }

    public static void closeQuietly(AutoCloseable r) {
        if (r == null) return;
        try {
            r.close();
        } catch (Exception ignored) {
        }
    }
}
