package com.yashgamerx.core;

import java.net.InetAddress;
import java.nio.file.Path;

/** A file-transfer protocol: a receiver that listens and a sender that pushes one file to it. */
public interface Protocol {
    enum Transport { TCP, UDP, HYBRID }

    String name();

    String description();

    Transport transport();

    /** True if the protocol guarantees a complete, correct file (udp-blast does not). */
    default boolean reliable() {
        return true;
    }

    /**
     * Binds the receiver's sockets (port 0 = ephemeral) and starts waiting for exactly one
     * transfer, writing it to {@code out}. Must return as soon as the sockets are bound.
     */
    ReceiverHandle listen(TransferContext ctx, InetAddress bind, int port, Path out) throws Exception;

    /** Sends {@code file}; returns when the protocol considers the transfer finished. */
    void send(TransferContext ctx, Path file, InetAddress host, int tcpPort, int udpPort) throws Exception;
}
