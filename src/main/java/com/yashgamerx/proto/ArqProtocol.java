package com.yashgamerx.proto;

import com.yashgamerx.core.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Classic sliding-window ARQ over pure UDP (textbook: Kurose & Ross; Tanenbaum), all with an
 * RFC 6298 retransmission timer and Karn's rule:
 * <ul>
 *   <li><b>udp-saw</b>   Stop-and-Wait (window 1).</li>
 *   <li><b>udp-gbn</b>   Go-Back-N: cumulative ACKs, receiver discards out-of-order, one timer,
 *       a timeout resends the whole window.</li>
 *   <li><b>udp-sr</b>    Selective Repeat: per-packet ACKs + cumulative ACK, out-of-order buffering,
 *       per-packet timers, fast retransmit after 3 later packets are acknowledged.</li>
 *   <li><b>udp-sr-aimd</b> Selective Repeat with TCP-Reno-like congestion control: slow start from
 *       IW10, additive increase, multiplicative decrease once per window on loss, cwnd=1 on timeout.</li>
 *   <li><b>udp-sr-hybla</b> Selective Repeat with TCP Hybla (Caini &amp; Firrincieli 2004): growth is
 *       normalised to a reference RTT0 (25 ms) with rho = RTT/RTT0; slow start adds 2^rho - 1 per ACK,
 *       congestion avoidance adds rho^2/cwnd per ACK, so long-RTT flows ramp up as fast as short ones.
 *       Losses are handled as in Reno.</li>
 *   <li><b>udp-sr-ledbat</b> Selective Repeat with LEDBAT (RFC 6817), a delay-based "scavenger" controller:
 *       queuing delay = current delay - base delay; cwnd += GAIN * (TARGET - queuing)/TARGET / cwnd per ACK,
 *       so it grows only while the queue is below TARGET. Halves on loss once per RTT, 1 on timeout.
 *       RFC 6817 measures one-way delay with receiver timestamps; this uses RTT samples (the reverse
 *       path carries only ACKs here, so RTT - minRTT approximates forward queuing delay).</li>
 * </ul>
 * The congestion-controlled variants use a large receive-window cap ({@code ccMaxWindow}) so that
 * cwnd, not the cap, limits them.
 * DATA: [type=1][seq:int32][chunkSize:int32][fileSize:int64][payload]; ACK: [type=2][cumAck:int32][seq:int32].
 */
public final class ArqProtocol implements Protocol {
    public enum Mode { SAW, GBN, SR, SR_AIMD, SR_HYBLA, SR_LEDBAT }

    static final byte DATA = 1, ACK = 2;
    static final int HDR = 17, ACK_LEN = 9, DUP_THRESH = 3;
    private final Mode mode;

    public ArqProtocol(Mode mode) {
        this.mode = mode;
    }

    public String name() {
        return switch (mode) {
            case SAW -> "udp-saw";
            case GBN -> "udp-gbn";
            case SR -> "udp-sr";
            case SR_AIMD -> "udp-sr-aimd";
            case SR_HYBLA -> "udp-sr-hybla";
            case SR_LEDBAT -> "udp-sr-ledbat";
        };
    }

    public String description() {
        return switch (mode) {
            case SAW -> "Stop-and-Wait ARQ over UDP (window = 1)";
            case GBN -> "Go-Back-N ARQ over UDP (cumulative ACK, fixed window)";
            case SR -> "Selective-Repeat ARQ over UDP (SACK, fast retransmit, fixed window)";
            case SR_AIMD -> "Selective-Repeat ARQ + Reno-style AIMD congestion control";
            case SR_HYBLA -> "Selective-Repeat ARQ + TCP Hybla congestion control (RTT-normalised growth)";
            case SR_LEDBAT -> "Selective-Repeat ARQ + LEDBAT delay-based scavenger congestion control (RFC 6817)";
        };
    }

    public Transport transport() {
        return Transport.UDP;
    }

    private boolean selective() {
        return mode != Mode.SAW && mode != Mode.GBN;
    }

    private boolean congestionControlled() {
        return mode == Mode.SR_AIMD || mode == Mode.SR_HYBLA || mode == Mode.SR_LEDBAT;
    }

    // ------------------------------------------------------------------ receiver

    public ReceiverHandle listen(TransferContext ctx, InetAddress bind, int port, Path out) throws Exception {
        ReceiverHandle rx = new ReceiverHandle(ctx);
        DatagramSocket sock = ctx.track(Net.udp(bind, port, ctx.cfg));
        rx.udpPort = sock.getLocalPort();
        boolean sel = selective();
        rx.run(name() + "-rx", () -> {
            byte[] buf = new byte[65_536];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            ByteBuffer bb = ByteBuffer.wrap(buf);
            byte[] ack = new byte[ACK_LEN];
            ByteBuffer ab = ByteBuffer.wrap(ack);
            DatagramPacket ap = new DatagramPacket(ack, ACK_LEN);
            SinkFile sink = null;
            BitSet have = new BitSet();
            int expected = 0, count = 0;
            sock.setSoTimeout(200);
            try {
                // keeps running after completion to re-ACK duplicates until the harness closes it
                while (!ctx.cancelled()) {
                    p.setLength(buf.length);
                    if (!Net.receive(sock, p)) continue;
                    if (buf[0] != DATA || p.getLength() < HDR) continue;
                    int seq = bb.getInt(1), chunk = bb.getInt(5);
                    long size = bb.getLong(9);
                    if (sink == null) {
                        sink = ctx.track(new SinkFile(out, size, chunk));
                        ctx.stats.totalChunks = sink.chunks;
                    }
                    if (seq < 0 || seq >= sink.chunks) continue;
                    boolean accept = sel ? !have.get(seq) : seq == expected;
                    if (accept) {
                        sink.write(seq, buf, HDR);
                        have.set(seq);
                        count++;
                        ctx.stats.chunksReceived.incrementAndGet();
                        ctx.stats.bytesReceived.addAndGet(sink.length(seq));
                        expected = have.nextClearBit(expected);
                        if (count == sink.chunks) rx.complete();
                    } else {
                        ctx.stats.duplicatePackets.incrementAndGet();
                    }
                    SocketAddress from = p.getSocketAddress();
                    ab.put(0, ACK).putInt(1, expected).putInt(5, seq);
                    ap.setSocketAddress(from);
                    Net.send(sock, ap);
                    ctx.stats.receiverBytes.addAndGet(ACK_LEN);
                }
            } finally {
                if (sink != null) sink.close();
            }
        });
        return rx;
    }

    // ------------------------------------------------------------------ sender

    private final class Sender {
        final TransferContext ctx;
        final int total, maxWindow;
        final RttEstimator rtt;
        final ReentrantLock lock = new ReentrantLock();
        final Condition changed = lock.newCondition();
        final BitSet acked = new BitSet(), retransmitted = new BitSet(), fastDone = new BitSet();
        final AtomicLongArray sentAt;
        final ArrayDeque<Integer> resend = new ArrayDeque<>();
        int base, next, highestAcked = -1, frScan;
        long gbnTimer, lastTimeoutEvent, lastProgress = System.nanoTime();
        double cwnd, ssthresh;
        int recoverPoint;
        volatile Throwable ackError;
        // delay state: min RTT (Hybla rho, LEDBAT base delay) and the last few samples (LEDBAT current delay)
        long minRtt = Long.MAX_VALUE;
        final long[] recent = new long[4];
        int recentN;

        Sender(TransferContext ctx, int total) {
            this.ctx = ctx;
            this.total = total;
            this.maxWindow = mode == Mode.SAW ? 1
                    : congestionControlled() ? Math.max(ctx.cfg.window, ctx.cfg.ccMaxWindow) : Math.max(1, ctx.cfg.window);
            this.rtt = new RttEstimator(ctx.cfg);
            this.sentAt = new AtomicLongArray(total);
            this.cwnd = mode == Mode.SR_LEDBAT ? 2 : ctx.cfg.initialCwnd; // RFC 6817 INIT_CWND = 2
            this.ssthresh = maxWindow;
        }

        int window() {
            return congestionControlled() ? (int) Math.max(1, Math.min(maxWindow, cwnd)) : maxWindow;
        }

        void lossEvent(int seq, boolean timeout) {
            if (!congestionControlled()) return; // Reno, Hybla and LEDBAT all halve on loss
            if (timeout) {
                ssthresh = Math.max(2, cwnd / 2);
                cwnd = 1;
                recoverPoint = next;
            } else if (seq >= recoverPoint) {   // at most one reduction per window of data
                ssthresh = Math.max(2, cwnd / 2);
                cwnd = ssthresh;
                recoverPoint = next;
            }
        }

        void onAck(int cum, int seq, long now) {
            lock.lock();
            try {
                if (selective()) {
                    if (seq >= 0 && seq < total && !acked.get(seq)) {
                        if (!retransmitted.get(seq)) delaySample(now - sentAt.get(seq));
                        acked.set(seq);
                        if (congestionControlled()) increase();
                        highestAcked = Math.max(highestAcked, seq);
                    }
                    if (cum > base) acked.set(base, Math.min(cum, total));
                    int oldBase = base;
                    base = acked.nextClearBit(base);
                    if (base > oldBase) rtt.resetBackoff();
                    // fast retransmit: a packet with >= DUP_THRESH later packets acknowledged is presumed lost
                    frScan = Math.max(frScan, base);
                    while (frScan <= highestAcked - DUP_THRESH && frScan < next) {
                        if (!acked.get(frScan) && !fastDone.get(frScan)) {
                            fastDone.set(frScan);
                            resend.add(frScan);
                            ctx.stats.fastRetransmits.incrementAndGet();
                            lossEvent(frScan, false);
                        }
                        frScan++;
                    }
                } else if (cum > base) {
                    int last = Math.min(cum, total) - 1;
                    if (!retransmitted.get(last)) rtt.sample(now - sentAt.get(last));
                    base = Math.min(cum, total);
                    gbnTimer = now;
                    rtt.resetBackoff();
                }
                lastProgress = now;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void delaySample(long r) {
            rtt.sample(r);
            minRtt = Math.min(minRtt, r);
            recent[recentN++ % recent.length] = r;
        }

        /** Per-ACK window growth of the congestion-controlled variants. */
        void increase() {
            switch (mode) {
                case SR_AIMD -> cwnd += cwnd < ssthresh ? 1 : 1 / cwnd;
                case SR_HYBLA -> {
                    double rho = minRtt == Long.MAX_VALUE ? 1
                            : Math.max(1, minRtt / (ctx.cfg.hyblaRtt0Ms * (double) Clock.MS));
                    // cap the slow-start step as Linux's Hybla does for very large rho
                    cwnd += cwnd < ssthresh ? Math.min(Math.pow(2, rho) - 1, 64) : rho * rho / cwnd;
                }
                case SR_LEDBAT -> {
                    if (recentN == 0) return;
                    long current = Long.MAX_VALUE;
                    for (int i = 0; i < Math.min(recentN, recent.length); i++) current = Math.min(current, recent[i]);
                    double target = ctx.cfg.ledbatTargetMs * (double) Clock.MS;
                    double offTarget = (target - (current - minRtt)) / target;
                    cwnd = Math.max(2, cwnd + offTarget / cwnd); // GAIN = 1, MIN_CWND = 2
                }
                default -> { }
            }
        }

        /** Collects the next batch of sequence numbers to (re)send; blocks briefly when the window is full. */
        int nextBatch(int[] batch, boolean[] isRetx) throws InterruptedException {
            lock.lock();
            try {
                for (;;) {
                    if (ackError != null) throw new IllegalStateException("ACK receiver failed", ackError);
                    if (base >= total || ctx.cancelled()) return -1;
                    long now = System.nanoTime(), rto = rtt.rtoNanos();
                    if (now - lastProgress > 3 * ctx.cfg.maxRtoMs * Clock.MS)
                        throw new IllegalStateException("peer unresponsive: no ACK for " + 3 * ctx.cfg.maxRtoMs + " ms");
                    long earliestDeadline = Long.MAX_VALUE;
                    if (selective()) {
                        boolean expired = false;
                        int end = Math.min(next, base + maxWindow);
                        for (int s = acked.nextClearBit(base); s < end; s = acked.nextClearBit(s + 1)) {
                            long due = sentAt.get(s) + rto;
                            if (due <= now) {
                                if (!resend.contains(s)) resend.add(s);
                                sentAt.set(s, now); // re-arm until actually sent
                                expired = true;
                            } else earliestDeadline = Math.min(earliestDeadline, due);
                        }
                        // back off / collapse cwnd at most once per RTO, not once per expired packet
                        if (expired && now - lastTimeoutEvent >= rto) {
                            lastTimeoutEvent = now;
                            ctx.stats.rtoTimeouts.incrementAndGet();
                            rtt.backoff();
                            lossEvent(base, true);
                        }
                    } else if (next > base) {
                        if (now - gbnTimer >= rto) {
                            resend.clear();
                            for (int s = base; s < next; s++) resend.add(s);
                            gbnTimer = now;
                            ctx.stats.rtoTimeouts.incrementAndGet();
                            rtt.backoff();
                        } else earliestDeadline = gbnTimer + rto;
                    }
                    int n = 0;
                    while (n < batch.length && !resend.isEmpty()) {
                        int s = resend.poll();
                        if (s < base || (selective() && acked.get(s))) continue;
                        retransmitted.set(s);
                        batch[n] = s;
                        isRetx[n++] = true;
                    }
                    int win = window();
                    boolean wasIdle = next == base;
                    while (n < batch.length && next < total && next - base < win) {
                        batch[n] = next++;
                        isRetx[n++] = false;
                    }
                    if (n > 0) {
                        if (!selective() && wasIdle) gbnTimer = now;
                        return n;
                    }
                    long wait = Math.min(Clock.MS, Math.max(50_000, earliestDeadline - now));
                    changed.awaitNanos(wait);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public void send(TransferContext ctx, Path file, InetAddress host, int tcpPort, int udpPort) throws Exception {
        DatagramSocket sock = ctx.track(Net.udp(null, 0, ctx.cfg));
        try (SourceFile src = new SourceFile(file, ctx.cfg.chunkSize)) {
            ctx.stats.totalChunks = src.chunks;
            Sender s = new Sender(ctx, src.chunks);
            Thread acker = Threads.start(name() + "-tx-ack", () -> {
                byte[] ab = new byte[64];
                ByteBuffer bb = ByteBuffer.wrap(ab);
                DatagramPacket ap = new DatagramPacket(ab, ab.length);
                sock.setSoTimeout(100);
                while (!sock.isClosed()) {
                    ap.setLength(ab.length);
                    if (!Net.receive(sock, ap)) continue;
                    if (ab[0] == ACK && ap.getLength() >= ACK_LEN) s.onAck(bb.getInt(1), bb.getInt(5), System.nanoTime());
                }
            }, e -> {
                if (!sock.isClosed()) s.ackError = e;
            });

            byte[] buf = new byte[HDR + src.chunk];
            ByteBuffer bb = ByteBuffer.wrap(buf);
            DatagramPacket p = new DatagramPacket(buf, buf.length, host, udpPort);
            Pacer pacer = new Pacer(ctx.cfg.udpRateBps);
            int[] batch = new int[32];
            boolean[] isRetx = new boolean[32];
            int n;
            while ((n = s.nextBatch(batch, isRetx)) > 0) {
                for (int i = 0; i < n; i++) {
                    int seq = batch[i];
                    bb.put(0, DATA).putInt(1, seq).putInt(5, src.chunk).putLong(9, src.size);
                    int len = HDR + src.read(seq, buf, HDR);
                    p.setLength(len);
                    pacer.pace(len + Net.UDP_IP_HEADER);
                    s.sentAt.set(seq, System.nanoTime());
                    Net.send(sock, p);
                    ctx.stats.senderBytes.addAndGet(len);
                    (isRetx[i] ? ctx.stats.retransPackets : ctx.stats.dataPackets).incrementAndGet();
                }
            }
            sock.close();
            acker.join(500);
            if (ctx.cancelled()) throw new IOException("cancelled");
        } finally {
            sock.close();
        }
    }
}
