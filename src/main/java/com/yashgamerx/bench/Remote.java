package com.yashgamerx.bench;

import com.yashgamerx.core.*;
import com.yashgamerx.proto.Protocols;

import java.io.PrintWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Two-host mode for real testbeds (e.g. two Linux VMs shaped with tc-netem). Receiver and sender
 * run in different processes, so completion time is measured at the sender: from the first byte
 * until the protocol's final acknowledgement (udp-blast has none, so it measures send time only).
 */
public final class Remote {
    private Remote() {}

    public static final String HELP = """
            server - receive files for real two-host experiments (one transfer at a time, in a loop)
                --protocol rbudp  --port 9000  [--bind 0.0.0.0] [--out received] [--count 0 (=forever)] [--linger 2000]
            send   - send a file to a running server and append sender-side measurements to a CSV
                --protocol rbudp  --host 10.0.0.2 --port 9000 --file data.bin [--trials 5] [--label wan-netem]
                [--csv results/remote.csv] [--gap 3000 ms between trials] [--timeout 600]
            """;

    public static void server(Args a) throws Exception {
        Protocol p = Protocols.get(a.get("protocol", "tcp-stream"));
        int port = a.getInt("port", 9000);
        InetAddress bind = InetAddress.getByName(a.get("bind", "0.0.0.0"));
        Path outDir = Path.of(a.get("out", "received"));
        int count = a.getInt("count", 0);
        long linger = a.getLong("linger", 2000);
        Config cfg = a.config();
        a.rejectUnknown();
        Files.createDirectories(outDir);
        System.out.printf("%s receiver on %s:%d (tcp+udp), writing to %s%n", p.name(), bind.getHostAddress(), port, outDir.toAbsolutePath());
        for (int i = 1; count == 0 || i <= count; i++) {
            TransferStats st = new TransferStats();
            TransferContext ctx = new TransferContext(cfg.copy(), st);
            Path out = outDir.resolve(p.name() + "-" + i + ".bin");
            ReceiverHandle rx = p.listen(ctx, bind, port, out);
            while (!rx.await(1000)) { /* wait for a sender */ }
            long doneAt = System.nanoTime();
            // keep answering (e.g. re-ACKing the last packets) so the sender can finish cleanly
            while (System.nanoTime() - doneAt < linger * Clock.MS) Clock.sleepUntil(System.nanoTime() + 50 * Clock.MS);
            rx.close();
            if (rx.error() != null) {
                System.out.println("#" + i + " FAILED: " + rx.error());
                continue;
            }
            System.out.printf(Locale.ROOT, "#%d received %d bytes (%d chunks, %d duplicates, %d FEC-recovered) sha256=%s%n",
                    i, st.bytesReceived.get(), st.chunksReceived.get(), st.duplicatePackets.get(), st.fecRecovered.get(),
                    Files.exists(out) ? TrialRunner.sha256(out) : "-");
            if (!a.flag("keep")) TrialRunner.delete(out);
        }
    }

    public static void send(Args a) throws Exception {
        Protocol p = Protocols.get(a.get("protocol", "tcp-stream"));
        InetAddress host = InetAddress.getByName(a.get("host", "127.0.0.1"));
        int port = a.getInt("port", 9000);
        Path file = Path.of(a.get("file", "data.bin"));
        int trials = a.getInt("trials", 1);
        String label = a.get("label", "remote");
        Path csv = Path.of(a.get("csv", "results/remote.csv"));
        long gap = a.getLong("gap", 3000);
        long timeoutMs = a.getLong("timeout", 600) * 1000;
        Config cfg = a.config();
        a.rejectUnknown();
        long size = Files.size(file);
        String hash = TrialRunner.sha256(file);
        System.out.printf("sending %s (%s, sha256=%s) with %s to %s:%d%n", file, Units.bytes(size), hash, p.name(), host.getHostAddress(), port);
        if (csv.getParent() != null) Files.createDirectories(csv.getParent());
        boolean header = !Files.exists(csv);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            if (header) w.println(TrialResult.HEADER);
            for (int t = 1; t <= trials; t++) {
                TransferStats st = new TransferStats();
                TransferContext ctx = new TransferContext(cfg.copy(), st);
                long start = System.nanoTime();
                String status = "OK", err = null;
                java.util.concurrent.atomic.AtomicReference<Throwable> fail = new java.util.concurrent.atomic.AtomicReference<>();
                Thread th = Threads.start("send", () -> p.send(ctx, file, host, port, port), fail::set);
                th.join(timeoutMs);
                if (fail.get() != null) {
                    status = "ERROR";
                    err = fail.get().toString();
                } else if (th.isAlive()) {
                    status = "TIMEOUT";
                    ctx.cancel();
                }
                long end = System.nanoTime();
                ctx.cancel();
                double ms = (end - start) / 1e6;
                double gp = size * 8.0 / (ms / 1000) / 1e6;
                TrialResult r = new TrialResult(label, p.name(), size, t, status, ms, ms, gp, Double.NaN, false,
                        (double) st.senderBytes.get() / size, st.senderBytes.get(), 0, st.dataPackets.get(),
                        st.retransPackets.get(), st.fecPackets.get(), 0, st.rounds.get(), st.rtoTimeouts.get(),
                        st.fastRetransmits.get(), 0, 0, 0, 0, 0, true, st.finalRateBps / 1e6, err);
                w.println(r.csv());
                w.flush();
                System.out.printf(Locale.ROOT, "trial %d %s: %.3f s, %.2f Mb/s, %d data + %d retrans packets%n",
                        t, status, ms / 1000, gp, st.dataPackets.get(), st.retransPackets.get());
                if (t < trials) Clock.sleepUntil(System.nanoTime() + gap * Clock.MS);
            }
        }
        System.out.println("appended to " + csv.toAbsolutePath() + " (verify integrity with the server's sha256 lines)");
    }
}
