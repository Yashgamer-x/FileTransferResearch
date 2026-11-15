package com.yashgamerx.bench;

import com.yashgamerx.core.*;
import com.yashgamerx.emu.NetworkEmulator;
import com.yashgamerx.emu.Scenario;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

/** Runs one sender/receiver pair through (optionally) the emulator and measures it. */
public final class TrialRunner {
    private TrialRunner() {}

    public static TrialResult run(Protocol p, Scenario sc, Config cfg, Path src, String srcHash, long size,
                                  int trial, long seed, long timeoutMs, Path workDir) throws IOException {
        TransferStats st = new TransferStats();
        TransferContext ctx = new TransferContext(cfg, st);
        Path out = workDir.resolve("rx-" + p.name() + "-" + trial + "-" + System.nanoTime() + ".bin");
        NetworkEmulator emu = null;
        ReceiverHandle rx = null;
        Thread tx = null;
        AtomicReference<Throwable> txErr = new AtomicReference<>();
        boolean timedOut = false;
        String setupError = null;
        long start = 0;
        try {
            InetAddress lo = InetAddress.getLoopbackAddress();
            rx = p.listen(ctx, lo, 0, out);
            int tp = rx.tcpPort, up = rx.udpPort;
            if (sc.emulated()) {
                emu = new NetworkEmulator(sc, seed, cfg.tcpBufferBytes, cfg.udpBufferBytes);
                int[] ports = emu.start(lo.getHostAddress(), tp, up);
                tp = ports[0];
                up = ports[1];
            }
            final int tcpPort = tp, udpPort = up;
            start = System.nanoTime();
            st.startNanos = start;
            tx = Threads.start("sender-" + p.name(), () -> {
                p.send(ctx, src, lo, tcpPort, udpPort);
                st.senderDoneNanos = System.nanoTime();
            }, txErr::set);
            boolean finished = rx.await(timeoutMs);
            long left = timeoutMs - (System.nanoTime() - start) / Clock.MS;
            tx.join(Math.max(1, left));
            timedOut = !finished || tx.isAlive();
        } catch (Exception e) {
            setupError = e.toString();
        } finally {
            ctx.cancel();
            if (emu != null) emu.close();
            if (tx != null) {
                try {
                    tx.join(3000);
                } catch (InterruptedException ignored) {
                }
            }
        }

        double durMs = st.receiverDoneNanos > 0 ? (st.receiverDoneNanos - start) / 1e6 : Double.NaN;
        double senderMs = st.senderDoneNanos > 0 ? (st.senderDoneNanos - start) / 1e6 : Double.NaN;
        long delivered = st.bytesReceived.get();
        String status, err = setupError;
        boolean integrity = false;
        if (setupError != null) status = "ERROR";
        else if (rx.error() != null) {
            status = "ERROR";
            err = "receiver: " + rx.error();
        } else if (timedOut) status = "TIMEOUT";
        else if (txErr.get() != null) {
            status = "ERROR";
            err = "sender: " + txErr.get();
        } else {
            integrity = Files.exists(out) && Files.size(out) == size && sha256(out).equals(srcHash);
            status = integrity ? "OK" : (p.reliable() ? "CORRUPT" : "INCOMPLETE");
        }
        if (integrity) delivered = size;
        if (status.equals("TIMEOUT")) durMs = timeoutMs; // lower bound on the true completion time
        double goodput = Double.isNaN(durMs) || durMs <= 0 ? Double.NaN : delivered * 8.0 / (durMs / 1000.0) / 1e6;
        long txBytes = st.senderBytes.get(), rxBytes = st.receiverBytes.get();
        boolean lossApplies = !sc.emulated() || p.transport() != Protocol.Transport.TCP || sc.loss().meanLoss() == 0;
        delete(out);
        return new TrialResult(sc.name(), p.name(), size, trial, status, durMs, senderMs, goodput,
                100.0 * delivered / size, integrity, (double) (txBytes + rxBytes) / size, txBytes, rxBytes,
                st.dataPackets.get(), st.retransPackets.get(), st.fecPackets.get(), st.fecRecovered.get(),
                st.rounds.get(), st.rtoTimeouts.get(), st.fastRetransmits.get(), st.duplicatePackets.get(),
                emu == null ? 0 : emu.forward.queueDrops.get(), emu == null ? 0 : emu.forward.randomLosses.get(),
                emu == null ? 0 : emu.reverse.queueDrops.get(), emu == null ? 0 : emu.reverse.randomLosses.get(),
                lossApplies, st.finalRateBps / 1e6, err);
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Windows refuses to delete a file while a mapping is alive; mappings are released asynchronously. */
    static void delete(Path p) {
        for (int i = 0; i < 20; i++) {
            try {
                Files.deleteIfExists(p);
                return;
            } catch (IOException e) {
                System.gc();
                Clock.sleepUntil(System.nanoTime() + 50 * Clock.MS);
            }
        }
        p.toFile().deleteOnExit();
    }
}
