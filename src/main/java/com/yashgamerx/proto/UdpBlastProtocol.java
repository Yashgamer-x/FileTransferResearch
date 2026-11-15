package com.yashgamerx.proto;

import com.yashgamerx.core.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * Reference point 2: raw UDP blast with no feedback and no retransmission. It gives the upper bound
 * on speed for a given pacing rate and the lower bound on correctness (delivered fraction = 1 - loss).
 * DATA: [type=1][seq:int32][chunkSize:int32][fileSize:int64][payload]; END: [type=2][chunks:int32].
 */
public final class UdpBlastProtocol implements Protocol {
    static final byte DATA = 1, END = 2;
    static final int HDR = 17;

    public String name() {
        return "udp-blast";
    }

    public String description() {
        return "Raw UDP blast, no reliability (speed upper bound / correctness lower bound)";
    }

    public Transport transport() {
        return Transport.UDP;
    }

    public boolean reliable() {
        return false;
    }

    public ReceiverHandle listen(TransferContext ctx, InetAddress bind, int port, Path out) throws Exception {
        ReceiverHandle rx = new ReceiverHandle(ctx);
        DatagramSocket sock = ctx.track(Net.udp(bind, port, ctx.cfg));
        rx.udpPort = sock.getLocalPort();
        rx.run("udp-blast-rx", () -> {
            byte[] buf = new byte[65_536];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            ByteBuffer bb = ByteBuffer.wrap(buf);
            SinkFile sink = null;
            BitSet have = new BitSet();
            long lastNew = 0;
            int count = 0;
            boolean ended = false;
            sock.setSoTimeout(200);
            try {
                while (!ctx.cancelled()) {
                    p.setLength(buf.length);
                    if (!Net.receive(sock, p)) {
                        if (sink == null) continue;            // still waiting for the first packet
                        if (ended || System.nanoTime() - lastNew > ctx.cfg.blastIdleMs * Clock.MS) break;
                        continue;
                    }
                    byte type = buf[0];
                    if (type == END) {
                        if (!ended) sock.setSoTimeout(50);     // drain stragglers, then stop
                        ended = true;
                        continue;
                    }
                    if (type != DATA || p.getLength() < HDR) continue;
                    int seq = bb.getInt(1), chunk = bb.getInt(5);
                    long size = bb.getLong(9);
                    if (sink == null) {
                        sink = ctx.track(new SinkFile(out, size, chunk));
                        ctx.stats.totalChunks = sink.chunks;
                    }
                    if (seq < 0 || seq >= sink.chunks) continue;
                    if (have.get(seq)) {
                        ctx.stats.duplicatePackets.incrementAndGet();
                        continue;
                    }
                    sink.write(seq, buf, HDR);
                    have.set(seq);
                    count++;
                    ctx.stats.chunksReceived.incrementAndGet();
                    ctx.stats.bytesReceived.addAndGet(sink.length(seq));
                    lastNew = System.nanoTime();
                    if (count == sink.chunks) break;
                }
            } finally {
                if (sink != null) sink.close();
            }
            if (lastNew > 0) rx.complete(lastNew);
            else rx.fail(new java.io.IOException("no data received"));
        });
        return rx;
    }

    public void send(TransferContext ctx, Path file, InetAddress host, int tcpPort, int udpPort) throws Exception {
        DatagramSocket sock = ctx.track(Net.udp(null, 0, ctx.cfg));
        try (SourceFile src = new SourceFile(file, ctx.cfg.chunkSize)) {
            ctx.stats.totalChunks = src.chunks;
            byte[] buf = new byte[HDR + src.chunk];
            ByteBuffer bb = ByteBuffer.wrap(buf);
            DatagramPacket p = new DatagramPacket(buf, buf.length, host, udpPort);
            Pacer pacer = new Pacer(ctx.cfg.udpRateBps);
            for (int i = 0; i < src.chunks && !ctx.cancelled(); i++) {
                bb.put(0, DATA).putInt(1, i).putInt(5, src.chunk).putLong(9, src.size);
                int len = HDR + src.read(i, buf, HDR);
                p.setLength(len);
                pacer.pace(len + Net.UDP_IP_HEADER);
                Net.send(sock, p);
                ctx.stats.dataPackets.incrementAndGet();
                ctx.stats.senderBytes.addAndGet(len);
            }
            bb.put(0, END).putInt(1, src.chunks);
            p.setLength(5);
            for (int i = 0; i < 5; i++) {
                Net.send(sock, p);
                ctx.stats.senderBytes.addAndGet(5);
                Clock.sleepUntil(System.nanoTime() + Clock.MS);
            }
        }
        sock.close();
    }
}
