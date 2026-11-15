package com.yashgamerx.proto;

import com.yashgamerx.core.*;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Baseline 1b: TCP with NIO SocketChannel and FileChannel.transferTo/transferFrom, which lets the
 * OS use zero-copy paths (sendfile / TransmitFile) where available. Same wire format as tcp-stream.
 */
public final class TcpNioProtocol implements Protocol {
    public String name() {
        return "tcp-nio";
    }

    public String description() {
        return "TCP, NIO SocketChannel + FileChannel.transferTo/From (zero-copy capable)";
    }

    public Transport transport() {
        return Transport.TCP;
    }

    public ReceiverHandle listen(TransferContext ctx, InetAddress bind, int port, Path out) throws IOException {
        ReceiverHandle rx = new ReceiverHandle(ctx);
        ServerSocketChannel ssc = ctx.track(ServerSocketChannel.open());
        ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        ssc.setOption(StandardSocketOptions.SO_RCVBUF, ctx.cfg.tcpBufferBytes);
        ssc.bind(new InetSocketAddress(bind, port), 1);
        rx.tcpPort = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
        rx.run("tcp-nio-rx", () -> {
            SocketChannel sc = ctx.track(ssc.accept());
            ByteBuffer hdr = ByteBuffer.allocate(8);
            readFully(sc, hdr);
            long size = hdr.flip().getLong();
            receiveInto(sc, out, 0, size, true);
            ctx.stats.bytesReceived.addAndGet(size);
            rx.complete();
            sc.write(ByteBuffer.wrap(new byte[]{1}));
            ctx.stats.receiverBytes.incrementAndGet();
            sc.read(ByteBuffer.allocate(1)); // wait for sender close
            sc.close();
        });
        return rx;
    }

    public void send(TransferContext ctx, Path file, InetAddress host, int tcpPort, int udpPort) throws IOException {
        SocketChannel sc = ctx.track(connect(ctx, host, tcpPort));
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = fc.size();
            writeFully(sc, ByteBuffer.allocate(8).putLong(size).flip());
            sendRange(sc, fc, 0, size);
            ctx.stats.senderBytes.addAndGet(8 + size);
        }
        ByteBuffer ack = ByteBuffer.allocate(1);
        if (sc.read(ack) != 1 || ack.get(0) != 1) throw new IOException("no completion ACK from receiver");
        sc.close();
    }

    static SocketChannel connect(TransferContext ctx, InetAddress host, int port) throws IOException {
        SocketChannel sc = SocketChannel.open();
        sc.setOption(StandardSocketOptions.SO_SNDBUF, ctx.cfg.tcpBufferBytes);
        sc.setOption(StandardSocketOptions.SO_RCVBUF, ctx.cfg.tcpBufferBytes);
        sc.connect(new InetSocketAddress(host, port));
        return sc;
    }

    static void sendRange(SocketChannel sc, FileChannel fc, long pos, long len) throws IOException {
        long end = pos + len;
        while (pos < end) pos += fc.transferTo(pos, end - pos, sc);
    }

    /** Receives exactly {@code len} bytes into the file at {@code pos} via transferFrom. */
    static void receiveInto(SocketChannel sc, Path out, long pos, long len, boolean truncate) throws IOException {
        StandardOpenOption[] opts = truncate
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE};
        try (FileChannel fc = FileChannel.open(out, opts)) {
            long end = pos + len;
            while (pos < end) {
                long n = fc.transferFrom(sc, pos, end - pos);
                if (n > 0) {
                    pos += n;
                    continue;
                }
                // transferFrom returns 0 both at EOF and when nothing is ready; disambiguate with a read
                ByteBuffer b = ByteBuffer.allocate((int) Math.min(64 * 1024, end - pos));
                if (sc.read(b) < 0) throw new EOFException("stream ended with " + (end - pos) + " bytes missing");
                b.flip();
                while (b.hasRemaining()) pos += fc.write(b, pos);
            }
        }
    }

    static void readFully(SocketChannel sc, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) if (sc.read(b) < 0) throw new EOFException();
    }

    static void writeFully(SocketChannel sc, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) sc.write(b);
    }
}
