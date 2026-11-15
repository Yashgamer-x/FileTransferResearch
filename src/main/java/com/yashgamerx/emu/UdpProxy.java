package com.yashgamerx.emu;

import com.yashgamerx.core.Net;

import java.io.IOException;
import java.net.*;

/**
 * Datagram relay: the sender talks to {@link #port()}, the proxy forwards through the
 * forward {@link Link} to the real receiver, and returns replies through the reverse link.
 */
final class UdpProxy implements AutoCloseable {
    private final DatagramSocket front, back;
    private final DelayLine fwdLine = new DelayLine("emu-udp-fwd"), revLine = new DelayLine("emu-udp-rev");
    private volatile SocketAddress client;

    UdpProxy(InetSocketAddress target, Link fwd, Link rev, int bufferBytes) throws IOException {
        InetAddress lo = InetAddress.getLoopbackAddress();
        front = new DatagramSocket(new InetSocketAddress(lo, 0));
        back = new DatagramSocket(new InetSocketAddress(lo, 0));
        for (DatagramSocket s : new DatagramSocket[]{front, back}) {
            s.setReceiveBufferSize(bufferBytes);
            s.setSendBufferSize(bufferBytes);
        }
        start("emu-udp-in", front, fwd, fwdLine, true, target);
        start("emu-udp-out", back, rev, revLine, false, target);
    }

    int port() {
        return front.getLocalPort();
    }

    private void start(String name, DatagramSocket in, Link link, DelayLine line, boolean forward, InetSocketAddress target) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[65_536];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            DatagramSocket out = forward ? back : front;
            try {
                while (!in.isClosed()) {
                    p.setLength(buf.length);
                    if (!Net.receive(in, p)) continue;
                    if (forward) client = p.getSocketAddress();
                    SocketAddress dst = forward ? target : client;
                    if (dst == null) continue;
                    int len = p.getLength();
                    long at = link.admitDatagram(len + Net.UDP_IP_HEADER, System.nanoTime());
                    if (at < 0) continue;
                    byte[] copy = new byte[len];
                    System.arraycopy(buf, 0, copy, 0, len);
                    line.schedule(at, () -> {
                        try {
                            Net.send(out, new DatagramPacket(copy, len, dst));
                        } catch (IOException ignored) {
                        }
                    });
                }
            } catch (IOException ignored) {
                // socket closed
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() {
        front.close();
        back.close();
        fwdLine.close();
        revLine.close();
    }
}
