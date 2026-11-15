package com.yashgamerx.core;

import java.util.concurrent.atomic.AtomicLong;

/** Counters filled in by both ends of a transfer. Byte counts are application-layer bytes including protocol headers. */
public final class TransferStats {
    public final AtomicLong senderBytes = new AtomicLong();      // everything the sender wrote (data + control)
    public final AtomicLong receiverBytes = new AtomicLong();    // everything the receiver wrote back (ACKs, reports)
    public final AtomicLong dataPackets = new AtomicLong();      // first transmissions of data chunks
    public final AtomicLong retransPackets = new AtomicLong();   // re-transmissions of data chunks
    public final AtomicLong fecPackets = new AtomicLong();       // parity packets sent
    public final AtomicLong fecRecovered = new AtomicLong();     // chunks rebuilt from parity
    public final AtomicLong rounds = new AtomicLong();           // RBUDP blast rounds
    public final AtomicLong rtoTimeouts = new AtomicLong();      // retransmission timer expirations
    public final AtomicLong fastRetransmits = new AtomicLong();
    public final AtomicLong duplicatePackets = new AtomicLong(); // data packets the receiver already had
    public final AtomicLong chunksReceived = new AtomicLong();   // unique chunks stored by the receiver
    public final AtomicLong bytesReceived = new AtomicLong();    // unique payload bytes stored by the receiver

    public volatile long totalChunks;
    public volatile long startNanos;
    public volatile long receiverDoneNanos;
    public volatile long senderDoneNanos;
    public volatile double finalRateBps;

    public synchronized void markReceiverDone(long nanos) {
        if (receiverDoneNanos == 0) receiverDoneNanos = nanos;
    }
}
