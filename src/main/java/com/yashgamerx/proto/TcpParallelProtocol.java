package com.yashgamerx.proto;

import com.yashgamerx.core.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parallel TCP striping in the style of GridFTP / bbcp: the file is split into N contiguous
 * stripes, each sent on its own TCP connection. With real loss this multiplies the aggregate
 * congestion window by N (Hacker et al., 2002). Header per stream:
 * [fileSize:int64][streams:int32][offset:int64][length:int64].
 */
public final class TcpParallelProtocol implements Protocol {
    public String name() {
        return "tcp-parallel";
    }

    public String description() {
        return "N parallel TCP streams over file stripes (GridFTP-style), NIO zero-copy";
    }

    public Transport transport() {
        return Transport.TCP;
    }

    public ReceiverHandle listen(TransferContext ctx, InetAddress bind, int port, Path out) throws IOException {
        ReceiverHandle rx = new ReceiverHandle(ctx);
        ServerSocketChannel ssc = ctx.track(ServerSocketChannel.open());
        ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        ssc.setOption(StandardSocketOptions.SO_RCVBUF, ctx.cfg.tcpBufferBytes);
        ssc.bind(new InetSocketAddress(bind, port), 64);
        rx.tcpPort = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
        rx.run("tcp-par-rx", () -> {
            int expected = Integer.MAX_VALUE, accepted = 0;
            CountDownLatch done = null;
            List<SocketChannel> conns = new ArrayList<>();
            AtomicReference<Throwable> err = new AtomicReference<>();
            while (accepted < expected) {
                SocketChannel sc = ctx.track(ssc.accept());
                ByteBuffer h = ByteBuffer.allocate(28);
                TcpNioProtocol.readFully(sc, h);
                h.flip();
                long size = h.getLong();
                int streams = h.getInt();
                long off = h.getLong(), len = h.getLong();
                if (accepted == 0) {
                    expected = streams;
                    done = new CountDownLatch(streams);
                    try (RandomAccessFile raf = new RandomAccessFile(out.toFile(), "rw")) {
                        raf.setLength(0);
                        raf.setLength(size);
                    }
                    ctx.stats.totalChunks = streams;
                }
                accepted++;
                conns.add(sc);
                CountDownLatch d = done;
                Threads.start("tcp-par-rx-" + accepted, () -> {
                    TcpNioProtocol.receiveInto(sc, out, off, len, false);
                    ctx.stats.bytesReceived.addAndGet(len);
                    d.countDown();
                }, e -> {
                    err.compareAndSet(null, e);
                    d.countDown();
                });
            }
            done.await();
            if (err.get() != null) throw new IOException("stream failed", err.get());
            rx.complete();
            for (SocketChannel sc : conns) {
                sc.write(ByteBuffer.wrap(new byte[]{1}));
                ctx.stats.receiverBytes.incrementAndGet();
            }
        });
        return rx;
    }

    public void send(TransferContext ctx, Path file, InetAddress host, int tcpPort, int udpPort) throws Exception {
        int n = Math.max(1, ctx.cfg.parallelStreams);
        long size;
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
            size = fc.size();
        }
        long stripe = (size + n - 1) / n;
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        int streams = (int) Math.min(n, (size + stripe - 1) / stripe);
        for (int i = 0; i < streams; i++) {
            long off = i * stripe, len = Math.min(stripe, size - off);
            threads.add(Threads.start("tcp-par-tx-" + i, () -> {
                SocketChannel sc = ctx.track(TcpNioProtocol.connect(ctx, host, tcpPort));
                ByteBuffer h = ByteBuffer.allocate(28).putLong(size).putInt(streams).putLong(off).putLong(len).flip();
                TcpNioProtocol.writeFully(sc, h);
                try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
                    TcpNioProtocol.sendRange(sc, fc, off, len);
                }
                ctx.stats.senderBytes.addAndGet(28 + len);
                ByteBuffer ack = ByteBuffer.allocate(1);
                if (sc.read(ack) != 1) throw new IOException("no ACK on stream");
                sc.close();
            }, e -> err.compareAndSet(null, e)));
        }
        for (Thread t : threads) t.join();
        if (err.get() != null) throw new IOException("parallel stream failed", err.get());
    }
}
