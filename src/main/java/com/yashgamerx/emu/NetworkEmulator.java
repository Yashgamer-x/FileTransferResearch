package com.yashgamerx.emu;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * In-process network emulator: a pair of {@link Link}s (forward = sender to receiver,
 * reverse = receiver to sender) shared by a UDP relay and a TCP relay.
 */
public final class NetworkEmulator implements AutoCloseable {
    public final Link forward, reverse;
    private UdpProxy udp;
    private TcpProxy tcp;
    private final int tcpWindowBytes, bufferBytes;

    public NetworkEmulator(Scenario s, long seed, int tcpWindowBytes, int bufferBytes) {
        long oneWay = (long) (s.rttMs() / 2 * 1e6);
        long jitter = (long) (s.jitterMs() * 1e6);
        forward = new Link(s.rateBps(), oneWay, jitter, s.queueBytes(), s.loss().copy(), seed);
        reverse = new Link(s.rateBps(), oneWay, jitter, s.queueBytes(),
                s.lossOnReverse() ? s.loss().copy() : LossModel.none(), seed * 31 + 7);
        this.tcpWindowBytes = tcpWindowBytes;
        this.bufferBytes = bufferBytes;
    }

    /** Starts relays in front of the given receiver ports (negative = not used) and returns {tcpPort, udpPort}. */
    public int[] start(String host, int tcpPort, int udpPort) throws IOException {
        int[] ports = {-1, -1};
        if (tcpPort > 0) {
            tcp = new TcpProxy(new InetSocketAddress(host, tcpPort), forward, reverse, tcpWindowBytes, bufferBytes);
            ports[0] = tcp.port();
        }
        if (udpPort > 0) {
            udp = new UdpProxy(new InetSocketAddress(host, udpPort), forward, reverse, bufferBytes);
            ports[1] = udp.port();
        }
        return ports;
    }

    @Override
    public void close() {
        if (udp != null) udp.close();
        if (tcp != null) tcp.close();
    }
}
