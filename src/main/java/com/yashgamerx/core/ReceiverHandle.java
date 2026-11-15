package com.yashgamerx.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A listening receiver. Protocols bind their sockets in {@code listen()}, publish the
 * ports here, run the transfer on background threads and call {@link #complete()}
 * once the whole file is on disk (or, for best-effort protocols, once they gave up).
 */
public final class ReceiverHandle implements AutoCloseable {
    private final TransferContext ctx;
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile Throwable error;
    private volatile boolean completed;
    public volatile int tcpPort = -1;
    public volatile int udpPort = -1;

    public ReceiverHandle(TransferContext ctx) {
        this.ctx = ctx;
    }

    /** Runs {@code body} on a daemon thread; an exception (unless cancelled) fails the receiver. */
    public Thread run(String name, Threads.Body body) {
        return Threads.start(name, body, this::fail);
    }

    public void complete() {
        complete(System.nanoTime());
    }

    public void complete(long whenNanos) {
        ctx.stats.markReceiverDone(whenNanos);
        completed = true;
        latch.countDown();
    }

    public void fail(Throwable t) {
        if (!ctx.cancelled() && !completed && error == null) error = t;
        latch.countDown();
    }

    /** @return true when the receiver finished (successfully or not) within the timeout. */
    public boolean await(long timeoutMs) throws InterruptedException {
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean completed() {
        return completed;
    }

    public Throwable error() {
        return error;
    }

    @Override
    public void close() {
        ctx.cancel();
    }
}
