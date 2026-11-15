package com.yashgamerx.emu;

import com.yashgamerx.core.Clock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * TCP relay through the emulated links. A user-space proxy terminates TCP, so to keep the
 * end-to-end window/RTT relationship each direction only lets {@code windowBytes} be "in flight"
 * across the emulated pipe: credit for a segment returns one reverse-path delay after it is
 * delivered (i.e. when the ACK would arrive). Throughput is therefore bounded by
 * min(link rate, window / RTT) like a flow-control-limited TCP connection.
 *
 * <p>Limitation (documented in README): packet loss is NOT applied to TCP here; a user-space
 * relay cannot drop segments of a kernel TCP stream. Use netem/clumsy for TCP-under-loss runs.
 */
final class TcpProxy implements AutoCloseable {
    private static final int SEGMENT = 16 * 1024, MSS = 1460, TCP_IP_HEADER = 40;
    private final ServerSocket server;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    TcpProxy(InetSocketAddress target, Link fwd, Link rev, int windowBytes, int bufferBytes) throws IOException {
        server = new ServerSocket();
        server.setReceiveBufferSize(bufferBytes);
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64);
        Thread acceptor = new Thread(() -> {
            try {
                while (!closed) {
                    Socket c = server.accept();
                    Socket s = new Socket();
                    s.setSendBufferSize(bufferBytes);
                    s.setReceiveBufferSize(bufferBytes);
                    s.connect(target, 10_000);
                    for (Socket x : new Socket[]{c, s}) {
                        x.setTcpNoDelay(true);
                        sockets.add(x);
                    }
                    new Pump(c, s, fwd, rev.delayNs(), windowBytes).start("emu-tcp-fwd");
                    new Pump(s, c, rev, fwd.delayNs(), windowBytes).start("emu-tcp-rev");
                }
            } catch (IOException ignored) {
            }
        }, "emu-tcp-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private record Segment(long deliverAt, byte[] data, int len) {}

    private record Credit(long at, int bytes) {}

    private static final class Pump {
        private final Socket src, dst;
        private final Link link;
        private final long returnDelayNs;
        private final int window;
        private final LinkedBlockingQueue<Segment> pipe = new LinkedBlockingQueue<>();
        private final ConcurrentLinkedQueue<Credit> credits = new ConcurrentLinkedQueue<>();

        Pump(Socket src, Socket dst, Link link, long returnDelayNs, int window) {
            this.src = src;
            this.dst = dst;
            this.link = link;
            this.returnDelayNs = returnDelayNs;
            this.window = window;
        }

        void start(String name) {
            Thread r = new Thread(this::readLoop, name + "-rd");
            Thread w = new Thread(this::writeLoop, name + "-wr");
            r.setDaemon(true);
            w.setDaemon(true);
            r.start();
            w.start();
        }

        private void readLoop() {
            long inflight = 0, lastDelivery = 0;
            byte[] buf = new byte[SEGMENT];
            try {
                InputStream in = src.getInputStream();
                for (;;) {
                    // 1. window credit
                    for (;;) {
                        long now = System.nanoTime();
                        Credit c;
                        while ((c = credits.peek()) != null && c.at <= now) {
                            credits.poll();
                            inflight -= c.bytes;
                        }
                        if (window - inflight >= MSS) break;
                        c = credits.peek();
                        Clock.sleepUntil(c != null ? c.at : now + Clock.MS);
                    }
                    int n = in.read(buf, 0, (int) Math.min(buf.length, window - inflight));
                    if (n < 0) break;
                    int wire = n + TCP_IP_HEADER * ((n + MSS - 1) / MSS);
                    // 2. back-pressure instead of tail drop: wait until the shared queue has room
                    if (link.nsPerByte() > 0) {
                        for (;;) {
                            long now = System.nanoTime();
                            long excess = link.backlogBytes(now) + wire - link.queueLimitBytes();
                            if (excess <= 0) break;
                            Clock.sleepUntil(now + (long) (excess * link.nsPerByte()));
                        }
                    }
                    long at = Math.max(lastDelivery, link.admitStream(wire, System.nanoTime()));
                    lastDelivery = at;
                    inflight += n;
                    pipe.put(new Segment(at, buf.clone(), n));
                }
                pipe.put(new Segment(0, null, -1));
            } catch (IOException | InterruptedException e) {
                pipe.offer(new Segment(0, null, -2));
            }
        }

        private void writeLoop() {
            try {
                OutputStream out = dst.getOutputStream();
                for (;;) {
                    Segment s = pipe.take();
                    if (s.len == -1) {
                        dst.shutdownOutput();
                        return;
                    }
                    if (s.len < 0) {
                        dst.close();
                        src.close();
                        return;
                    }
                    Clock.sleepUntil(s.deliverAt);
                    out.write(s.data, 0, s.len);
                    credits.add(new Credit(System.nanoTime() + returnDelayNs, s.len));
                }
            } catch (IOException | InterruptedException e) {
                try {
                    src.close();
                    dst.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
