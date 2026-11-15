package com.yashgamerx.proto;

import com.yashgamerx.core.*;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Reliable Blast UDP (He, Leigh, Yu, DeFanti, IEEE Cluster 2002): TCP control channel + UDP data.
 * <ol>
 *   <li>Sender announces the file on TCP, receiver answers READY.</li>
 *   <li>Blast phase: every outstanding chunk is sent over UDP at a fixed (user-chosen) rate.</li>
 *   <li>Sender signals END-OF-ROUND on TCP; the receiver waits for the UDP path to fall quiet and
 *       answers with a bitmap of the chunks it is still missing.</li>
 *   <li>Repeat 2-3 with only the missing chunks until the bitmap is empty.</li>
 * </ol>
 * Optional extension ({@code fecGroup > 0}): first-round XOR parity over groups of k chunks lets the
 * receiver rebuild one lost chunk per group without a retransmission round.
 *
 * <p>UDP DATA: [type=1][seq:int32][payload]; PARITY: [type=3][group:int32][xor of k padded payloads].
 * TCP: HELLO(fileSize:int64, chunk:int32, fecGroup:int32) / READY(byte) / ROUND_END(round:int32, sent:int32)
 * / REPORT(missing:int32, bitmapLen:int32, bitmap bytes).
 */
public final class RbudpProtocol implements Protocol {
    static final byte DATA = 1, PARITY = 3, READY = 9;
    static final int HDR = 5;
    private final boolean fec;

    public RbudpProtocol(boolean fec) {
        this.fec = fec;
    }

    public String name() {
        return fec ? "rbudp-fec" : "rbudp";
    }

    public String description() {
        return fec ? "RBUDP + first-round XOR parity FEC (1 parity per k chunks)"
                : "Reliable Blast UDP: TCP control, UDP blast rounds, missing-chunk bitmap";
    }

    public Transport transport() {
        return Transport.HYBRID;
    }

    // ------------------------------------------------------------------ receiver

    private static final class RxState {
        final SinkFile sink;
        final BitSet have = new BitSet();
        final int k;
        final int[] groupHave;
        final Map<Integer, byte[]> parity = new HashMap<>();
        int count;
        volatile long lastPacket;
        final byte[] tmp;

        RxState(SinkFile sink, int k) {
            this.sink = sink;
            this.k = k;
            this.groupHave = k > 0 ? new int[(sink.chunks + k - 1) / k] : new int[0];
            this.tmp = new byte[sink.chunk];
        }

        int groupSize(int g) {
            return Math.min(k, sink.chunks - g * k);
        }
    }

    public ReceiverHandle listen(TransferContext ctx, InetAddress bind, int port, Path out) throws Exception {
        ReceiverHandle rx = new ReceiverHandle(ctx);
        ServerSocket ss = ctx.track(Net.tcpServer(bind, port, ctx.cfg, 1));
        DatagramSocket udp = ctx.track(Net.udp(bind, port, ctx.cfg));
        rx.tcpPort = ss.getLocalPort();
        rx.udpPort = udp.getLocalPort();
        rx.run(name() + "-rx-ctl", () -> {
            Net.Control c = ctx.track(new Net.Control(ss.accept(), ctx.stats.receiverBytes));
            long size = c.in.readLong();
            int chunk = c.in.readInt(), k = c.in.readInt();
            SinkFile sink = ctx.track(new SinkFile(out, size, chunk));
            ctx.stats.totalChunks = sink.chunks;
            RxState st = new RxState(sink, k);
            rx.run(name() + "-rx-udp", () -> udpLoop(ctx, rx, udp, st));
            c.out.writeByte(READY);
            c.out.flush();
            for (;;) {
                int round = c.in.readInt();
                c.in.readInt(); // packets sent this round (informational)
                // let in-flight datagrams land: wait until the UDP socket has been quiet for a moment
                long quiet = ctx.cfg.rbudpQuietMs * Clock.MS, t0 = System.nanoTime();
                while (System.nanoTime() - Math.max(st.lastPacket, t0) < quiet) Clock.sleepUntil(System.nanoTime() + Clock.MS);
                byte[] bitmap;
                int missing;
                synchronized (st) {
                    BitSet miss = new BitSet(sink.chunks);
                    miss.set(0, sink.chunks);
                    miss.andNot(st.have);
                    missing = miss.cardinality();
                    bitmap = miss.toByteArray();
                }
                c.out.writeInt(missing);
                c.out.writeInt(bitmap.length);
                c.out.write(bitmap);
                c.out.flush();
                if (missing == 0) break;
            }
            sink.close();
            c.in.read(); // wait for sender to close
        });
        return rx;
    }

    private void udpLoop(TransferContext ctx, ReceiverHandle rx, DatagramSocket udp, RxState st) throws IOException {
        byte[] buf = new byte[65_536];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        ByteBuffer bb = ByteBuffer.wrap(buf);
        udp.setSoTimeout(200);
        while (!ctx.cancelled()) {
            p.setLength(buf.length);
            if (!Net.receive(udp, p)) continue;
            st.lastPacket = System.nanoTime();
            if (p.getLength() < HDR) continue;
            int idx = bb.getInt(1);
            synchronized (st) {
                if (buf[0] == DATA) {
                    if (idx < 0 || idx >= st.sink.chunks) continue;
                    if (st.have.get(idx)) {
                        ctx.stats.duplicatePackets.incrementAndGet();
                        continue;
                    }
                    store(ctx, st, idx, buf, HDR);
                    if (st.k > 0) {
                        int g = idx / st.k;
                        if (st.groupHave[g] == st.groupSize(g)) st.parity.remove(g);
                        else tryRecover(ctx, st, g);
                    }
                } else if (buf[0] == PARITY && st.k > 0) {
                    if (idx < 0 || idx >= st.groupHave.length || st.groupHave[idx] == st.groupSize(idx)) continue;
                    byte[] par = new byte[st.sink.chunk];
                    System.arraycopy(buf, HDR, par, 0, Math.min(par.length, p.getLength() - HDR));
                    st.parity.put(idx, par);
                    tryRecover(ctx, st, idx);
                }
                if (st.count == st.sink.chunks) {
                    rx.complete();
                    return;
                }
            }
        }
    }

    private static void store(TransferContext ctx, RxState st, int idx, byte[] src, int off) {
        st.sink.write(idx, src, off);
        st.have.set(idx);
        st.count++;
        if (st.k > 0) st.groupHave[idx / st.k]++;
        ctx.stats.chunksReceived.incrementAndGet();
        ctx.stats.bytesReceived.addAndGet(st.sink.length(idx));
    }

    /** If group g misses exactly one chunk and its parity is here, rebuild the chunk by XOR. */
    private static void tryRecover(TransferContext ctx, RxState st, int g) {
        byte[] par = st.parity.get(g);
        if (par == null || st.groupHave[g] != st.groupSize(g) - 1) return;
        int first = g * st.k, last = first + st.groupSize(g);
        int lost = -1;
        byte[] acc = par.clone();
        for (int i = first; i < last; i++) {
            if (!st.have.get(i)) {
                lost = i;
                continue;
            }
            int len = st.sink.read(i, st.tmp, 0);
            for (int j = 0; j < len; j++) acc[j] ^= st.tmp[j];
        }
        st.parity.remove(g);
        store(ctx, st, lost, acc, 0);
        ctx.stats.fecRecovered.incrementAndGet();
    }

    // ------------------------------------------------------------------ sender

    public void send(TransferContext ctx, Path file, InetAddress host, int tcpPort, int udpPort) throws Exception {
        Net.Control c = ctx.track(new Net.Control(Net.tcpConnect(host, tcpPort, ctx.cfg), ctx.stats.senderBytes));
        DatagramSocket udp = ctx.track(Net.udp(null, 0, ctx.cfg));
        int k = fec ? Math.max(2, ctx.cfg.fecGroup) : 0;
        try (SourceFile src = new SourceFile(file, ctx.cfg.chunkSize)) {
            ctx.stats.totalChunks = src.chunks;
            c.out.writeLong(src.size);
            c.out.writeInt(src.chunk);
            c.out.writeInt(k);
            c.out.flush();
            if (c.in.readByte() != READY) throw new IOException("bad READY");

            byte[] buf = new byte[HDR + src.chunk];
            byte[] par = new byte[HDR + src.chunk];
            ByteBuffer bb = ByteBuffer.wrap(buf), pb = ByteBuffer.wrap(par);
            DatagramPacket p = new DatagramPacket(buf, buf.length, host, udpPort);
            DatagramPacket pp = new DatagramPacket(par, par.length, host, udpPort);
            Pacer pacer = new Pacer(ctx.cfg.udpRateBps);
            BitSet todo = new BitSet(src.chunks);
            todo.set(0, src.chunks);
            for (int round = 1; ; round++) {
                ctx.stats.rounds.incrementAndGet();
                int sent = 0;
                boolean parityRound = k > 0 && round == 1;
                for (int i = todo.nextSetBit(0); i >= 0 && !ctx.cancelled(); i = todo.nextSetBit(i + 1)) {
                    bb.put(0, DATA).putInt(1, i);
                    int len = HDR + src.read(i, buf, HDR);
                    p.setLength(len);
                    pacer.pace(len + Net.UDP_IP_HEADER);
                    Net.send(udp, p);
                    sent++;
                    ctx.stats.senderBytes.addAndGet(len);
                    (round == 1 ? ctx.stats.dataPackets : ctx.stats.retransPackets).incrementAndGet();
                    if (parityRound) {
                        if (i % k == 0) java.util.Arrays.fill(par, HDR, par.length, (byte) 0);
                        for (int j = HDR; j < len; j++) par[j] ^= buf[j];
                        if (i % k == k - 1 || i == src.chunks - 1) {
                            pb.put(0, PARITY).putInt(1, i / k);
                            pacer.pace(par.length + Net.UDP_IP_HEADER);
                            Net.send(udp, pp);
                            ctx.stats.fecPackets.incrementAndGet();
                            ctx.stats.senderBytes.addAndGet(par.length);
                        }
                    }
                }
                c.out.writeInt(round);
                c.out.writeInt(sent);
                c.out.flush();
                int missing = c.in.readInt();
                byte[] bitmap = new byte[c.in.readInt()];
                c.in.readFully(bitmap);
                if (missing == 0) break;
                todo = BitSet.valueOf(bitmap);
            }
        }
        c.close();
        udp.close();
    }
}
