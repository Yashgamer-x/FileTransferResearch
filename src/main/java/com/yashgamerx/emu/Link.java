package com.yashgamerx.emu;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One direction of an emulated bottleneck: a FIFO drop-tail queue drained at a fixed rate,
 * followed by a propagation delay (with optional jitter) and a loss process. All flows in that
 * direction (UDP datagrams and TCP byte segments) share it, so they compete for capacity.
 */
public final class Link {
    private final double nsPerByte;  // 0 = infinite capacity
    private final long delayNs, jitterNs, queueLimitBytes;
    private final LossModel loss;
    private final Random rnd;
    private long idleAt;             // time at which the transmitter finishes the current backlog
    private long lastLossEval = Long.MIN_VALUE;
    private static final int SLOT_BYTES = 1500; // burst-loss time unit: one MTU at the link rate

    public final AtomicLong offeredPackets = new AtomicLong();
    public final AtomicLong queueDrops = new AtomicLong();
    public final AtomicLong randomLosses = new AtomicLong();

    public Link(long rateBps, long delayNs, long jitterNs, long queueLimitBytes, LossModel loss, long seed) {
        this.nsPerByte = rateBps <= 0 ? 0 : 8e9 / rateBps;
        this.delayNs = delayNs;
        this.jitterNs = jitterNs;
        this.queueLimitBytes = queueLimitBytes;
        this.loss = loss;
        this.rnd = new Random(seed);
    }

    public long delayNs() {
        return delayNs;
    }

    /**
     * Offers a datagram to the queue. Returns its delivery time (System.nanoTime base) or -1
     * if it was tail-dropped or lost. A lost packet still consumed transmission time.
     */
    public synchronized long admitDatagram(int wireBytes, long now) {
        offeredPackets.incrementAndGet();
        long start = Math.max(now, idleAt);
        if (nsPerByte > 0 && (start - now) / nsPerByte + wireBytes > queueLimitBytes) {
            queueDrops.incrementAndGet();
            return -1;
        }
        idleAt = start + (long) (wireBytes * nsPerByte);
        long slots = 1;
        if (nsPerByte > 0 && lastLossEval != Long.MIN_VALUE)
            slots = Math.max(1, (long) ((start - lastLossEval) / (SLOT_BYTES * nsPerByte)));
        lastLossEval = start;
        if (loss.drop(rnd, slots)) {
            randomLosses.incrementAndGet();
            return -1;
        }
        return idleAt + propagation();
    }

    /** Queue backlog in bytes at time {@code now}. */
    public synchronized long backlogBytes(long now) {
        return nsPerByte == 0 ? 0 : (long) (Math.max(0, idleAt - now) / nsPerByte);
    }

    public long queueLimitBytes() {
        return queueLimitBytes;
    }

    public double nsPerByte() {
        return nsPerByte;
    }

    /** Admits stream bytes without loss or drop (TCP back-pressure is handled by the caller). */
    public synchronized long admitStream(int wireBytes, long now) {
        long start = Math.max(now, idleAt);
        idleAt = start + (long) (wireBytes * nsPerByte);
        return idleAt + delayNs;
    }

    private long propagation() {
        if (jitterNs <= 0) return delayNs;
        long j = (long) (rnd.nextGaussian() * jitterNs);
        return Math.max(0, delayNs + j);
    }
}
