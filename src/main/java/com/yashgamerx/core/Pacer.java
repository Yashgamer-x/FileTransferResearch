package com.yashgamerx.core;

/** Packet pacer: inter-packet gap = size / rate, with ~1 ms of burst credit to absorb timer overshoot. */
public final class Pacer {
    private static final long BURST_CREDIT_NS = Clock.MS;
    private volatile double nsPerByte;
    private long next;

    public Pacer(double bitsPerSecond) {
        setRate(bitsPerSecond);
    }

    public void setRate(double bitsPerSecond) {
        nsPerByte = bitsPerSecond <= 0 ? 0 : 8e9 / bitsPerSecond;
    }

    public double rateBps() {
        double n = nsPerByte;
        return n == 0 ? 0 : 8e9 / n;
    }

    /** Blocks until a packet of {@code wireBytes} may be sent. */
    public void pace(int wireBytes) {
        double n = nsPerByte;
        if (n == 0) return;
        long now = System.nanoTime();
        if (next > now) {
            Clock.sleepUntil(next);
            now = next;
        }
        next = Math.max(next, now - BURST_CREDIT_NS) + (long) (wireBytes * n);
    }
}
