package com.yashgamerx.proto;

import com.yashgamerx.core.*;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.BitSet;

/**
 * Tsunami-style transfer (Meiss, Indiana University, 2002; tsunami-udp): TCP control + UDP data.
 * Unlike RBUDP there are no rounds: the sender streams blocks continuously at a target rate while
 * the receiver, every {@code tsunamiUpdateMs}, sends (a) retransmission requests for gaps it has
 * seen and (b) its recent loss rate. Retransmissions are served ahead of new blocks. The sender
 * adapts its inter-packet delay multiplicatively: ipd *= 25/24 when the loss rate exceeds the
 * threshold (default 7.5%), otherwise ipd *= 5/6 down to the target (constants follow tsunami-udp's
 * defaults). A missing block is re-requested if it is still absent ~2 RTT + 2 update periods later.
 *
 * <p>UDP: [type=1 original | 4 retransmit][seq:int32][payload].
 * TCP: S->R HELLO(fileSize:int64, chunk:int32) R->S READY, S->R START(rttNanos:int64);
 * R->S REQ(0x01, n:int32, seq...) | ERR(0x02, ppm:int32) | DONE(0x03).
 */
public final class TsunamiProtocol implements Protocol {
    static final byte ORIG = 1, RETX = 4, READY = 9, MSG_REQ = 1, MSG_ERR = 2, MSG_DONE = 3;
    static final int HDR = 5, MAX_REQ = 8192;

    public String name() {
        return "tsunami";
    }

    public String description() {
        return "Tsunami-style: continuous rate-based UDP stream, periodic NAK + loss-rate feedback over TCP";
    }

    public Transport transport() {
        return Transport.HYBRID;
    }

    // ------------------------------------------------------------------ receiver

    public ReceiverHandle listen(TransferContext ctx, InetAddress bind, int port, Path out) throws Exception {
        ReceiverHandle rx = new ReceiverHandle(ctx);
        ServerSocket ss = ctx.track(Net.tcpServer(bind, port, ctx.cfg, 1));
        DatagramSocket udp = ctx.track(Net.udp(bind, port, ctx.cfg));
        rx.tcpPort = ss.getLocalPort();
        rx.udpPort = udp.getLocalPort();
        rx.run("tsunami-rx-ctl", () -> {
            Net.Control c = ctx.track(new Net.Control(ss.accept(), ctx.stats.receiverBytes));
            long size = c.in.readLong();
            int chunk = c.in.readInt();
            SinkFile sink = ctx.track(new SinkFile(out, size, chunk));
            int total = sink.chunks;
            ctx.stats.totalChunks = total;
            c.out.writeByte(READY);
            c.out.flush();
            long rtt = c.in.readLong();
            long update = ctx.cfg.tsunamiUpdateMs * Clock.MS;
            long reRequestAfter = Math.max(3 * update, 2 * rtt + 2 * update);
            long idleAfter = rtt + 3 * update;

            Rx st = new Rx(total);
            Thread udpThread = rx.run("tsunami-rx-udp", () -> udpLoop(ctx, udp, sink, st));
            long[] lastReq = new long[total];
            int[] req = new int[MAX_REQ];
            long next = System.nanoTime() + update;
            while (!ctx.cancelled()) {
                Clock.sleepUntil(next);
                next += update;
                long now = System.nanoTime();
                int n = 0, lostIv, gotIv;
                boolean done;
                synchronized (st) {
                    done = st.count == total;
                    lostIv = st.gapsInInterval;
                    gotIv = st.origInInterval;
                    st.gapsInInterval = st.origInInterval = 0;
                    if (!done) {
                        // a silent path means the sender ran out of new blocks: treat the tail as missing too
                        boolean idle = now - st.lastPacket > idleAfter;
                        int limit = idle ? total : st.highest + 1;
                        for (int s = st.have.nextClearBit(0); s < limit && n < MAX_REQ; s = st.have.nextClearBit(s + 1)) {
                            if (lastReq[s] == 0 || now - lastReq[s] > reRequestAfter) {
                                lastReq[s] = now;
                                req[n++] = s;
                            }
                        }
                    }
                }
                if (done) {
                    rx.complete(); // keeps the earlier timestamp set when the last block landed
                    c.out.writeByte(MSG_DONE);
                    c.out.flush();
                    break;
                }
                int expected = lostIv + gotIv;
                c.out.writeByte(MSG_ERR);
                c.out.writeInt(expected == 0 ? 0 : (int) (1_000_000L * lostIv / expected));
                if (n > 0) {
                    c.out.writeByte(MSG_REQ);
                    c.out.writeInt(n);
                    for (int i = 0; i < n; i++) c.out.writeInt(req[i]);
                }
                c.out.flush();
            }
            udpThread.join(1000);
            sink.close();
            c.in.read();
        });
        return rx;
    }

    private static final class Rx {
        final BitSet have = new BitSet();
        int count, highest = -1, gapsInInterval, origInInterval;
        long lastPacket = System.nanoTime();
        final int total;

        Rx(int total) {
            this.total = total;
        }
    }

    private static void udpLoop(TransferContext ctx, DatagramSocket udp, SinkFile sink, Rx st) throws IOException {
        byte[] buf = new byte[65_536];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        ByteBuffer bb = ByteBuffer.wrap(buf);
        udp.setSoTimeout(200);
        while (!ctx.cancelled()) {
            p.setLength(buf.length);
            if (!Net.receive(udp, p) || p.getLength() < HDR) continue;
            int seq = bb.getInt(1);
            if (seq < 0 || seq >= st.total) continue;
            boolean fresh;
            synchronized (st) {
                st.lastPacket = System.nanoTime();
                fresh = !st.have.get(seq);
                if (buf[0] == ORIG) {
                    st.origInInterval++;
                    if (seq > st.highest) {
                        st.gapsInInterval += seq - st.highest - 1;
                        st.highest = seq;
                    }
                }
            }
            if (!fresh) {
                ctx.stats.duplicatePackets.incrementAndGet();
                continue;
            }
            sink.write(seq, buf, HDR);
            ctx.stats.chunksReceived.incrementAndGet();
            ctx.stats.bytesReceived.addAndGet(sink.length(seq));
            synchronized (st) {
                st.have.set(seq);
                if (seq > st.highest) st.highest = seq;
                if (++st.count == st.total) {
                    ctx.stats.markReceiverDone(System.nanoTime());
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ sender

    public void send(TransferContext ctx, Path file, InetAddress host, int tcpPort, int udpPort) throws Exception {
        Net.Control c = ctx.track(new Net.Control(Net.tcpConnect(host, tcpPort, ctx.cfg), ctx.stats.senderBytes));
        DatagramSocket udp = ctx.track(Net.udp(null, 0, ctx.cfg));
        double target = ctx.cfg.udpRateBps > 0 ? ctx.cfg.udpRateBps : ctx.cfg.tsunamiDefaultTargetBps;
        try (SourceFile src = new SourceFile(file, ctx.cfg.chunkSize)) {
            ctx.stats.totalChunks = src.chunks;
            long t0 = System.nanoTime();
            c.out.writeLong(src.size);
            c.out.writeInt(src.chunk);
            c.out.flush();
            if (c.in.readByte() != READY) throw new IOException("bad READY");
            long rtt = System.nanoTime() - t0;
            c.out.writeLong(rtt);
            c.out.flush();

            Tx st = new Tx(src.chunks);
            Pacer pacer = new Pacer(target);
            Thread ctl = Threads.start("tsunami-tx-ctl", () -> controlLoop(ctx, c, st, pacer, target), st::fail);

            byte[] buf = new byte[HDR + src.chunk];
            ByteBuffer bb = ByteBuffer.wrap(buf);
            DatagramPacket p = new DatagramPacket(buf, buf.length, host, udpPort);
            int nextNew = 0;
            while (!ctx.cancelled()) {
                int seq;
                boolean retx;
                synchronized (st) {
                    if (st.error != null) throw new IOException("control channel failed", st.error);
                    if (st.done) break;
                    Integer r = st.retx.poll();
                    if (r != null) {
                        st.queued.clear(r);
                        seq = r;
                        retx = true;
                    } else if (nextNew < src.chunks) {
                        seq = nextNew++;
                        retx = false;
                    } else {
                        st.wait(5);
                        continue;
                    }
                }
                bb.put(0, retx ? RETX : ORIG).putInt(1, seq);
                int len = HDR + src.read(seq, buf, HDR);
                p.setLength(len);
                pacer.pace(len + Net.UDP_IP_HEADER);
                Net.send(udp, p);
                ctx.stats.senderBytes.addAndGet(len);
                (retx ? ctx.stats.retransPackets : ctx.stats.dataPackets).incrementAndGet();
            }
            ctx.stats.finalRateBps = pacer.rateBps();
            ctl.join(1000);
        }
        c.close();
        udp.close();
    }

    private static final class Tx {
        final ArrayDeque<Integer> retx = new ArrayDeque<>();
        final BitSet queued;
        boolean done;
        Throwable error;

        Tx(int total) {
            queued = new BitSet(total);
        }

        synchronized void fail(Throwable t) {
            error = t;
            notifyAll();
        }
    }

    private static void controlLoop(TransferContext ctx, Net.Control c, Tx st, Pacer pacer, double target) throws IOException {
        double rate = target, threshold = ctx.cfg.tsunamiErrorThreshold;
        for (;;) {
            byte type = c.in.readByte();
            switch (type) {
                case MSG_DONE -> {
                    synchronized (st) {
                        st.done = true;
                        st.notifyAll();
                    }
                    return;
                }
                case MSG_ERR -> {
                    double err = c.in.readInt() / 1e6;
                    // ipd' = ipd * 25/24 (slower) or ipd * 5/6 (faster); rate is 1/ipd
                    rate = err > threshold ? rate * 24.0 / 25.0 : Math.min(target, rate * 6.0 / 5.0);
                    pacer.setRate(rate);
                }
                case MSG_REQ -> {
                    int n = c.in.readInt();
                    int[] seqs = new int[n];
                    for (int i = 0; i < n; i++) seqs[i] = c.in.readInt();
                    synchronized (st) {
                        for (int s : seqs) {
                            if (!st.queued.get(s)) {
                                st.queued.set(s);
                                st.retx.add(s);
                            }
                        }
                        st.notifyAll();
                    }
                }
                default -> throw new IOException("bad control message " + type);
            }
        }
    }
}
