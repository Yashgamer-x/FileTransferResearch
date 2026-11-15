package com.yashgamerx.core;

/**
 * Tunable parameters shared by every protocol implementation. One instance is
 * copied per trial so that a protocol can never leak state into the next run.
 */
public final class Config implements Cloneable {
    /** UDP payload bytes per data datagram (1400 keeps IP packets under a 1500 B MTU). */
    public int chunkSize = 1400;
    /** Target sending rate for paced UDP senders in bit/s; 0 = unpaced (as fast as the socket accepts). */
    public long udpRateBps = 0;
    /** Maximum sliding window (packets) for the ARQ protocols. */
    public int window = 512;
    /** Initial congestion window (packets) for AIMD, per RFC 6928. */
    public int initialCwnd = 10;
    /** Retransmission-timeout bounds (RFC 6298 uses a 1 s minimum; lowered so LAN scenarios are meaningful). */
    public long minRtoMs = 10, initialRtoMs = 1000, maxRtoMs = 10_000;
    /** SO_SNDBUF / SO_RCVBUF for TCP sockets. */
    public int tcpBufferBytes = 4 << 20;
    /** SO_SNDBUF / SO_RCVBUF for UDP sockets. */
    public int udpBufferBytes = 16 << 20;
    /** Number of concurrent TCP connections for tcp-parallel. */
    public int parallelStreams = 4;
    /** Data packets per XOR parity packet for rbudp-fec. */
    public int fecGroup = 16;
    /** Tsunami receiver feedback period (ms). */
    public int tsunamiUpdateMs = 20;
    /** Tsunami loss-rate threshold above which the sender slows down. */
    public double tsunamiErrorThreshold = 0.075;
    /** Tsunami target rate used when udpRateBps is 0 (Tsunami needs a finite target). */
    public long tsunamiDefaultTargetBps = 1_000_000_000L;
    /** udp-blast receiver gives up after this much silence. */
    public int blastIdleMs = 1000;
    /** RBUDP receiver waits for this much UDP silence after a round-end signal before reporting. */
    public int rbudpQuietMs = 10;

    public Config copy() {
        try {
            return (Config) clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public String toString() {
        return "chunkSize=" + chunkSize + " udpRateBps=" + udpRateBps + " window=" + window
                + " initialCwnd=" + initialCwnd + " rto[min,init,max]ms=" + minRtoMs + "," + initialRtoMs + "," + maxRtoMs
                + " tcpBuffer=" + tcpBufferBytes + " udpBuffer=" + udpBufferBytes
                + " parallelStreams=" + parallelStreams + " fecGroup=" + fecGroup
                + " tsunamiUpdateMs=" + tsunamiUpdateMs + " tsunamiErrorThreshold=" + tsunamiErrorThreshold
                + " blastIdleMs=" + blastIdleMs + " rbudpQuietMs=" + rbudpQuietMs;
    }
}
