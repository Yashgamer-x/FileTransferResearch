package com.yashgamerx.proto;

import com.yashgamerx.core.*;
import com.yashgamerx.fec.ReedSolomon;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
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
 * Optional first-round forward error correction over groups of k chunks:
 * <ul>
 *   <li><b>XOR</b> (rbudp-fec): one parity packet per group; repairs one loss per group.</li>
 *   <li><b>RS</b> (rbudp-rs): m Reed-Solomon parity packets per group (Cauchy RS over GF(2^8));
 *       repairs any m losses per group.</li>
 * </ul>
 * Losses beyond what FEC repairs fall back to RBUDP retransmission rounds.
 *
 * <p>UDP DATA: [type=1][seq:int32][payload]; PARITY: [type=3][group:int32][index:u8][parity payload].
 * TCP: HELLO(fileSize:int64, chunk:int32, fecKind:u8, k:int32, m:int32) / READY(byte) /
 * ROUND_END(round:int32, sent:int32) / REPORT(missing:int32, bitmapLen:int32, bitmap bytes).
 */
public final class RbudpProtocol implements Protocol {
    public enum Fec { NONE, XOR, RS }

    static final byte DATA = 1, PARITY = 3, READY = 9;
    static final int HDR = 5, PHDR = 6;
    private final Fec fec;

    public RbudpProtocol(Fec fec) {
        this.fec = fec;
    }

    public String name() {
        return switch (fec) {
            case NONE -> "rbudp";
            case XOR -> "rbudp-fec";
            case RS -> "rbudp-rs";
        };
    }

    public String description() {
        return switch (fec) {
            case NONE -> "Reliable Blast UDP: TCP control, UDP blast rounds, missing-chunk bitmap";
            case XOR -> "RBUDP + first-round XOR parity FEC (1 parity per k chunks)";
            case RS -> "RBUDP + first-round Reed-Solomon FEC (m parity per k chunks, repairs any m losses)";
        };
    }

    public Transport transport() {
        return Transport.HYBRID;
    }

    // ------------------------------------------------------------------ receiver

    private static final class RxState {
        final SinkFile sink;
        final BitSet have = new BitSet();
        final Fec fec;
        final int k, m;
        final int[] groupHave, parityHave;
        final Map<Integer, byte[][]> parity = new HashMap<>();
        final ReedSolomon rs;
        int count;
        volatile long lastPacket;
        final byte[] tmp;

        RxState(SinkFile sink, Fec fec, int k, int m) {
            this.sink = sink;
            this.fec = fec;
            this.k = k;
            this.m = m;
            int groups = k > 0 ? (sink.chunks + k - 1) / k : 0;
            this.groupHave = new int[groups];
            this.parityHave = new int[groups];
            this.rs = fec == Fec.RS ? new ReedSolomon(m) : null;
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
            int chunk = c.in.readInt();
            Fec kind = Fec.values()[c.in.readUnsignedByte()];
            int k = c.in.readInt(), m = c.in.readInt();
            SinkFile sink = ctx.track(new SinkFile(out, size, chunk));
            ctx.stats.totalChunks = sink.chunks;
            RxState st = new RxState(sink, kind, kind == Fec.NONE ? 0 : k, m);
            rx.run(name() + "-rx-udp", () -> udpLoop(ctx, rx, udp, st));
            c.out.writeByte(READY);
            c.out.flush();
            for (;;) {
                c.in.readInt(); // round
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
                } else if (buf[0] == PARITY && st.k > 0 && p.getLength() >= PHDR) {
                    int j = buf[5] & 0xff;
                    if (idx < 0 || idx >= st.groupHave.length || j >= st.m || st.groupHave[idx] == st.groupSize(idx)) continue;
                    byte[][] ps = st.parity.computeIfAbsent(idx, g -> new byte[st.m][]);
                    if (ps[j] != null) continue;
                    byte[] par = new byte[st.sink.chunk];
                    System.arraycopy(buf, PHDR, par, 0, Math.min(par.length, p.getLength() - PHDR));
                    ps[j] = par;
                    st.parityHave[idx]++;
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

    /** Rebuilds the missing chunks of group g once data + parity received cover the group size. */
    private static void tryRecover(TransferContext ctx, RxState st, int g) {
        int size = st.groupSize(g), have = st.groupHave[g];
        if (have == size || have + st.parityHave[g] < size) return;
        byte[][] ps = st.parity.get(g);
        if (ps == null) return;
        int first = g * st.k;
        if (st.fec == Fec.XOR) {
            int lost = -1;
            byte[] acc = ps[0].clone();
            for (int i = first; i < first + size; i++) {
                if (!st.have.get(i)) {
                    lost = i;
                    continue;
                }
                int len = st.sink.read(i, st.tmp, 0);
                for (int b = 0; b < len; b++) acc[b] ^= st.tmp[b];
            }
            store(ctx, st, lost, acc, 0);
            ctx.stats.fecRecovered.incrementAndGet();
        } else {
            byte[][] shards = new byte[size][];
            for (int i = 0; i < size; i++) {
                if (!st.have.get(first + i)) continue;
                shards[i] = new byte[st.sink.chunk];          // zero padding beyond a short last chunk
                st.sink.read(first + i, shards[i], 0);
            }
            if (!st.rs.decode(shards, size, ps, st.sink.chunk)) return;
            for (int i = 0; i < size; i++) {
                if (st.have.get(first + i)) continue;
                store(ctx, st, first + i, shards[i], 0);
                ctx.stats.fecRecovered.incrementAndGet();
            }
        }
        st.parity.remove(g);
    }

    // ------------------------------------------------------------------ sender

    public void send(TransferContext ctx, Path file, InetAddress host, int tcpPort, int udpPort) throws Exception {
        Net.Control c = ctx.track(new Net.Control(Net.tcpConnect(host, tcpPort, ctx.cfg), ctx.stats.senderBytes));
        DatagramSocket udp = ctx.track(Net.udp(null, 0, ctx.cfg));
        int k = fec == Fec.NONE ? 0 : Math.max(2, ctx.cfg.fecGroup);
        int m = fec == Fec.RS ? Math.max(1, ctx.cfg.rsParity) : fec == Fec.XOR ? 1 : 0;
        ReedSolomon rs = fec == Fec.RS ? new ReedSolomon(m) : null;
        try (SourceFile src = new SourceFile(file, ctx.cfg.chunkSize)) {
            ctx.stats.totalChunks = src.chunks;
            c.out.writeLong(src.size);
            c.out.writeInt(src.chunk);
            c.out.writeByte(fec.ordinal());
            c.out.writeInt(k);
            c.out.writeInt(m);
            c.out.flush();
            if (c.in.readByte() != READY) throw new IOException("bad READY");

            byte[] buf = new byte[HDR + src.chunk];
            byte[] par = new byte[PHDR + src.chunk];
            byte[] parPayload = new byte[src.chunk];
            byte[][] group = rs != null ? new byte[k][src.chunk] : null;
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
                    int dlen = src.read(i, buf, HDR), len = HDR + dlen;
                    p.setLength(len);
                    pacer.pace(len + Net.UDP_IP_HEADER);
                    Net.send(udp, p);
                    sent++;
                    ctx.stats.senderBytes.addAndGet(len);
                    (round == 1 ? ctx.stats.dataPackets : ctx.stats.retransPackets).incrementAndGet();
                    if (!parityRound) continue;
                    int pos = i % k;
                    if (rs != null) {
                        System.arraycopy(buf, HDR, group[pos], 0, dlen);
                        Arrays.fill(group[pos], dlen, src.chunk, (byte) 0);
                    } else {
                        if (pos == 0) Arrays.fill(parPayload, (byte) 0);
                        for (int b = 0; b < dlen; b++) parPayload[b] ^= buf[HDR + b];
                    }
                    if (pos == k - 1 || i == src.chunks - 1) {
                        for (int j = 0; j < m; j++) {
                            if (rs != null) rs.encode(group, pos + 1, j, parPayload, src.chunk);
                            pb.put(0, PARITY).putInt(1, i / k).put(5, (byte) j);
                            System.arraycopy(parPayload, 0, par, PHDR, src.chunk);
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
