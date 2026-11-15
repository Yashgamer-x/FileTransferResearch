package com.yashgamerx.proto;

import com.yashgamerx.core.*;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Baseline 1a: classic blocking TCP with java.net.Socket streams.
 * Wire format: [fileSize:int64][file bytes...]; receiver answers one ACK byte when the file is on disk.
 */
public final class TcpStreamProtocol implements Protocol {
    static final int BUF = 256 * 1024;

    public String name() {
        return "tcp-stream";
    }

    public String description() {
        return "TCP, java.net.Socket blocking streams (user-space copy)";
    }

    public Transport transport() {
        return Transport.TCP;
    }

    public ReceiverHandle listen(TransferContext ctx, InetAddress bind, int port, Path out) throws IOException {
        ReceiverHandle rx = new ReceiverHandle(ctx);
        ServerSocket ss = ctx.track(Net.tcpServer(bind, port, ctx.cfg, 1));
        rx.tcpPort = ss.getLocalPort();
        rx.run("tcp-stream-rx", () -> {
            Socket s = ctx.track(ss.accept());
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), BUF));
            long size = in.readLong();
            byte[] buf = new byte[BUF];
            try (OutputStream fos = Files.newOutputStream(out)) {
                long rem = size;
                while (rem > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, rem));
                    if (n < 0) throw new EOFException("stream ended with " + rem + " bytes missing");
                    fos.write(buf, 0, n);
                    rem -= n;
                }
            }
            ctx.stats.bytesReceived.addAndGet(size);
            rx.complete();
            OutputStream os = s.getOutputStream();
            os.write(1);
            os.flush();
            ctx.stats.receiverBytes.incrementAndGet();
            in.read(); // wait for the sender to close
            s.close();
        });
        return rx;
    }

    public void send(TransferContext ctx, Path file, InetAddress host, int tcpPort, int udpPort) throws IOException {
        Socket s = ctx.track(Net.tcpConnect(host, tcpPort, ctx.cfg));
        long size = Files.size(file);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), BUF));
        out.writeLong(size);
        try (InputStream fin = Files.newInputStream(file)) {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = fin.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.flush();
        ctx.stats.senderBytes.addAndGet(8 + size);
        if (s.getInputStream().read() != 1) throw new IOException("no completion ACK from receiver");
        s.close();
    }
}
