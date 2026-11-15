package com.yashgamerx.core;

/** Retransmission timer from RFC 6298 (Jacobson/Karels estimator with exponential backoff). */
public final class RttEstimator {
    private static final double ALPHA = 1.0 / 8, BETA = 1.0 / 4;
    private static final long G = Clock.MS; // clock granularity
    private final long minRto, maxRto;
    private double srtt = -1, rttvar;
    private long rto;

    public RttEstimator(Config cfg) {
        this.minRto = cfg.minRtoMs * Clock.MS;
        this.maxRto = cfg.maxRtoMs * Clock.MS;
        this.rto = cfg.initialRtoMs * Clock.MS;
    }

    public void sample(long rttNanos) {
        if (srtt < 0) {
            srtt = rttNanos;
            rttvar = rttNanos / 2.0;
        } else {
            rttvar = (1 - BETA) * rttvar + BETA * Math.abs(srtt - rttNanos);
            srtt = (1 - ALPHA) * srtt + ALPHA * rttNanos;
        }
        rto = clamp((long) (srtt + Math.max(G, 4 * rttvar)));
    }

    /**
     * Drops accumulated exponential backoff once new data is acknowledged (as Linux does by clearing
     * icsk_backoff). Without this, Karn's rule starves the estimator of samples after a timeout that
     * retransmitted the whole window, and the RTO ratchets up to its maximum.
     */
    public void resetBackoff() {
        if (srtt >= 0) rto = clamp((long) (srtt + Math.max(G, 4 * rttvar)));
    }

    public void backoff() {
        rto = clamp(rto * 2);
    }

    public long rtoNanos() {
        return rto;
    }

    private long clamp(long v) {
        return Math.max(minRto, Math.min(maxRto, v));
    }
}
