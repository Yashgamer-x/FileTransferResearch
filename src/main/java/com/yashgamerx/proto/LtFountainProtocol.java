package com.yashgamerx.proto;

import com.yashgamerx.core.*;
import com.yashgamerx.fec.LtCode;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.concurrent.CountDownLatch;

/**
 * Rateless transfer with an LT fountain code (Luby 2002; Byers, Luby, Mitzenmacher, Rege, SIGCOMM 1998
 * "digital fountain"). The sender never retransmits: it streams freshly encoded symbols (XORs of
 * random source chunks, robust soliton degrees) at the pacing rate until the receiver, which decodes
 * by belief propagation ("peeling"), reports DONE on the TCP control channel. Any ~K(1+eps) symbols
 * suffice regardless of which ones were lost, so loss pattern and burstiness do not matter; the cost
 * is the decoding overhead eps plus one RTT of symbols sent after the file was already decoded.
 *
 * <p>UDP SYMBOL: [type=1][seed:int32][chunk-size payload]. TCP: HELLO(fileSize:int64, chunk:int32,
 * c:float64, delta:float64) / READY(byte) / DONE(byte).
 */
public final class LtFountainProtocol implements Protocol {
    static final byte SYMBOL = 1, READY = 9, DONE = 10;
    static final int HDR = 5;

    public String name() {
        return "lt-fountain";
    }

    public String description() {
        return "LT fountain code (robust soliton, peeling decoder): rateless, no retransmissions";
    }

    public Transport transport() {
        return Transport.HYBRID;
    }

    // ------------------------------------------------------------------ receiver

    private static final class Sym {
        final byte[] data;
        int remaining, idxXor; // XOR of the still-unknown neighbour indices: the last one when remaining == 1
        boolean done;

        Sym(byte[] data, int remaining, int idxXor) {
            this.data = data;
            this.remaining = remaining;
            this.idxXor = idxXor;
        }
    }

    private static final class Decoder {
        final SinkFile sink;
        final LtCode code;
        final BitSet known = new BitSet();
        final ArrayList<Sym>[] adj;
        final ArrayDeque<Integer> ripple = new ArrayDeque<>();
        final byte[] tmp;
        final TransferStats stats;
        int count;

        @SuppressWarnings("unchecked")
        Decoder(SinkFile sink, LtCode code, TransferStats stats) {
            this.sink = sink;
            this.code = code;
            this.adj = new ArrayList[code.k];
            this.tmp = new byte[sink.chunk];
            this.stats = stats;
        }

        void xorChunk(byte[] into, int idx) {
            int len = sink.read(idx, tmp, 0);
            for (int b = 0; b < len; b++) into[b] ^= tmp[b];
        }

        void learn(int idx, byte[] data) {
            if (known.get(idx)) return;
            sink.write(idx, data, 0);
            known.set(idx);
            count++;
            stats.chunksReceived.incrementAndGet();
            stats.bytesReceived.addAndGet(sink.length(idx));
            ripple.add(idx);
        }

        /** Adds one received symbol and propagates; returns true if it contributed anything. */
        boolean add(int seed, byte[] payload) {
            int[] nb = code.neighbors(seed);
            int remaining = 0, ix = 0;
            for (int n : nb) {
                if (known.get(n)) xorChunk(payload, n);
                else {
                    remaining++;
                    ix ^= n;
                }
            }
            if (remaining == 0) return false;
            if (remaining == 1) learn(ix, payload);
            else {
                Sym s = new Sym(payload, remaining, ix);
                for (int n : nb) {
                    if (known.get(n)) continue;
                    if (adj[n] == null) adj[n] = new ArrayList<>(4);
                    adj[n].add(s);
                }
            }
            while (!ripple.isEmpty()) {
                int c = ripple.poll();
                ArrayList<Sym> list = adj[c];
                adj[c] = null;
                if (list == null) continue;
                int len = sink.read(c, tmp, 0);
                for (Sym s : list) {
                    if (s.done) continue;
                    for (int b = 0; b < len; b++) s.data[b] ^= tmp[b];
                    s.remaining--;
                    s.idxXor ^= c;
                    if (s.remaining <= 1) {
                        s.done = true;
                        if (s.remaining == 1) learn(s.idxXor, s.data);
                    }
                }
            }
            return true;
        }
    }

    public ReceiverHandle listen(TransferContext ctx, InetAddress bind, int port, Path out) throws Exception {
        ReceiverHandle rx = new ReceiverHandle(ctx);
        ServerSocket ss = ctx.track(Net.tcpServer(bind, port, ctx.cfg, 1));
        DatagramSocket udp = ctx.track(Net.udp(bind, port, ctx.cfg));
        rx.tcpPort = ss.getLocalPort();
        rx.udpPort = udp.getLocalPort();
        rx.run("lt-rx-ctl", () -> {
            Net.Control c = ctx.track(new Net.Control(ss.accept(), ctx.stats.receiverBytes));
            long size = c.in.readLong();
            int chunk = c.in.readInt();
            double lc = c.in.readDouble(), delta = c.in.readDouble();
            SinkFile sink = ctx.track(new SinkFile(out, size, chunk));
            ctx.stats.totalChunks = sink.chunks;
            Decoder dec = new Decoder(sink, new LtCode(sink.chunks, lc, delta), ctx.stats);
            CountDownLatch decoded = new CountDownLatch(1);
            rx.run("lt-rx-udp", () -> {
                byte[] buf = new byte[65_536];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ByteBuffer bb = ByteBuffer.wrap(buf);
                udp.setSoTimeout(200);
                while (!ctx.cancelled()) {
                    p.setLength(buf.length);
                    if (!Net.receive(udp, p) || buf[0] != SYMBOL || p.getLength() < HDR) continue;
                    byte[] payload = new byte[chunk];
                    System.arraycopy(buf, HDR, payload, 0, Math.min(chunk, p.getLength() - HDR));
                    if (!dec.add(bb.getInt(1), payload)) ctx.stats.duplicatePackets.incrementAndGet();
                    if (dec.count == sink.chunks) {
                        rx.complete();
                        decoded.countDown();
                        return;
                    }
                }
            });
            c.out.writeByte(READY);
            c.out.flush();
            decoded.await();
            c.out.writeByte(DONE);
            c.out.flush();
            sink.close();
            c.in.read(); // wait for the sender to close
        });
        return rx;
    }

    // ------------------------------------------------------------------ sender

    public void send(TransferContext ctx, Path file, InetAddress host, int tcpPort, int udpPort) throws Exception {
        Net.Control c = ctx.track(new Net.Control(Net.tcpConnect(host, tcpPort, ctx.cfg), ctx.stats.senderBytes));
        DatagramSocket udp = ctx.track(Net.udp(null, 0, ctx.cfg));
        try (SourceFile src = new SourceFile(file, ctx.cfg.chunkSize)) {
            ctx.stats.totalChunks = src.chunks;
            c.out.writeLong(src.size);
            c.out.writeInt(src.chunk);
            c.out.writeDouble(ctx.cfg.ltC);
            c.out.writeDouble(ctx.cfg.ltDelta);
            c.out.flush();
            if (c.in.readByte() != READY) throw new IOException("bad READY");
            LtCode code = new LtCode(src.chunks, ctx.cfg.ltC, ctx.cfg.ltDelta);

            var done = new java.util.concurrent.atomic.AtomicBoolean();
            var ctlError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread ctl = Threads.start("lt-tx-ctl", () -> {
                if (c.in.readByte() != DONE) throw new IOException("bad DONE");
                done.set(true);
            }, ctlError::set);

            byte[] buf = new byte[HDR + src.chunk];
            byte[] tmp = new byte[src.chunk];
            ByteBuffer bb = ByteBuffer.wrap(buf);
            DatagramPacket p = new DatagramPacket(buf, buf.length, host, udpPort);
            Pacer pacer = new Pacer(ctx.cfg.udpRateBps);
            long limit = (long) Math.ceil(src.chunks * ctx.cfg.ltMaxOverhead) + 64;
            for (int seed = 0; !done.get(); seed++) {
                if (ctx.cancelled()) throw new IOException("cancelled");
                if (ctlError.get() != null) throw new IOException("control channel failed", ctlError.get());
                if (seed >= limit) throw new IOException("receiver did not decode after " + limit + " symbols");
                java.util.Arrays.fill(buf, HDR, buf.length, (byte) 0);
                for (int n : code.neighbors(seed)) {
                    int len = src.read(n, tmp, 0);
                    for (int b = 0; b < len; b++) buf[HDR + b] ^= tmp[b];
                }
                bb.put(0, SYMBOL).putInt(1, seed);
                pacer.pace(buf.length + Net.UDP_IP_HEADER);
                Net.send(udp, p);
                ctx.stats.senderBytes.addAndGet(buf.length);
                // the first K symbols are the "data"; every symbol beyond K is coding redundancy
                (seed < src.chunks ? ctx.stats.dataPackets : ctx.stats.fecPackets).incrementAndGet();
            }
            ctl.join(1000);
        }
        c.close();
        udp.close();
    }
}
