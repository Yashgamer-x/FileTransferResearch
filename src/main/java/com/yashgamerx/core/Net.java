package com.yashgamerx.core;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Net {
    private Net() {}

    /** IPv4 (20) + UDP (8) header bytes, charged when a datagram crosses an emulated link. */
    public static final int UDP_IP_HEADER = 28;

    public static DatagramSocket udp(InetAddress bind, int port, Config cfg) throws SocketException {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setReceiveBufferSize(cfg.udpBufferBytes);
        s.setSendBufferSize(cfg.udpBufferBytes);
        s.bind(new InetSocketAddress(bind, port));
        return s;
    }

    public static ServerSocket tcpServer(InetAddress bind, int port, Config cfg, int backlog) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.setReceiveBufferSize(cfg.tcpBufferBytes);
        ss.bind(new InetSocketAddress(bind, port), backlog);
        return ss;
    }

    public static Socket tcpConnect(InetAddress host, int port, Config cfg) throws IOException {
        Socket s = new Socket();
        s.setSendBufferSize(cfg.tcpBufferBytes);
        s.setReceiveBufferSize(cfg.tcpBufferBytes);
        s.connect(new InetSocketAddress(host, port), 10_000);
        return s;
    }

    /**
     * Receives one datagram. Returns false on SO_TIMEOUT. Spurious errors caused by ICMP
     * "port unreachable" (Windows reports these as WSAECONNRESET on UDP sockets) are ignored.
     */
    public static boolean receive(DatagramSocket s, DatagramPacket p) throws IOException {
        int spurious = 0;
        for (;;) {
            try {
                s.receive(p);
                return true;
            } catch (SocketTimeoutException e) {
                return false;
            } catch (SocketException e) {
                if (s.isClosed() || ++spurious > 10_000) throw e;
            }
        }
    }

    public static void send(DatagramSocket s, DatagramPacket p) throws IOException {
        try {
            s.send(p);
        } catch (PortUnreachableException ignored) {
            // the peer went away; reliability logic (or the harness) decides what that means
        }
    }

    /** TCP control channel with byte accounting. */
    public static final class Control implements AutoCloseable {
        public final Socket socket;
        public final DataInputStream in;
        public final DataOutputStream out;

        public Control(Socket s, AtomicLong txCounter) throws IOException {
            this.socket = s;
            s.setTcpNoDelay(true);
            this.in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
            this.out = new DataOutputStream(new BufferedOutputStream(
                    new CountingOutputStream(s.getOutputStream(), txCounter), 1 << 16));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    public static final class CountingOutputStream extends FilterOutputStream {
        private final AtomicLong counter;

        public CountingOutputStream(OutputStream out, AtomicLong counter) {
            super(out);
            this.counter = counter;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            counter.incrementAndGet();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            counter.addAndGet(len);
        }
    }
}
