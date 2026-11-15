package com.yashgamerx.bench;

import com.yashgamerx.core.Config;
import com.yashgamerx.core.Protocol;
import com.yashgamerx.emu.Scenario;
import com.yashgamerx.proto.Protocols;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Full factorial benchmark: scenarios x file sizes x protocols x trials. Protocol order is
 * shuffled within every trial block so slow drifts of the host (thermal, background load)
 * do not bias one protocol. Every trial gets a fresh emulator seeded with (seed, trial), so a
 * given trial number sees the same loss pattern for every protocol where traffic patterns match.
 */
public final class BenchRunner {
    public static final String HELP = """
            bench  - run the in-process simulation benchmark
                --suite quick|standard|paper  preset (default quick); explicit options below override it
                --protocols all               comma list, 'all', and '-name' to exclude (e.g. all,-udp-saw)
                --scenarios lan,wan           built-in names, custom:rate=100,rtt=50,loss=0.01[,jitter=5,burst=10,queue=B]
                --sizes 1MB,10MB              file sizes
                --trials 5                    repetitions per cell          --warmup 1  JIT warm-up runs
                --timeout 120                 seconds per trial             --seed 42
                --rate-fraction 0.95          default UDP pacing = fraction x emulated link rate
                --no-skip                     keep running a protocol in a cell after it timed out once
                --out results                 output directory (a timestamped sub-directory is created)
            """;

    record Suite(String scenarios, String sizes, int trials, int timeoutSec) {}

    static final Map<String, Suite> SUITES = Map.of(
            "quick", new Suite("lan,wan,lossy-wan", "10MB", 3, 60),
            "standard", new Suite("loopback,lan,metro,wan,lossy-wan,burst-wan,high-loss,mobile", "1MB,10MB,50MB", 5, 120),
            "paper", new Suite("loopback,lan,metro,wan,lossy-wan,burst-wan,high-loss,mobile,satellite", "1MB,10MB,100MB", 10, 300));

    public static void main(Args a) throws Exception {
        Suite suite = SUITES.get(a.get("suite", "quick"));
        if (suite == null) throw new IllegalArgumentException("unknown suite; use quick|standard|paper");
        List<Protocol> protocols = Protocols.select(a.get("protocols", "all"));
        List<Scenario> scenarios = splitScenarios(a.get("scenarios", suite.scenarios)).stream().map(Scenario::parse).toList();
        List<Long> sizes = Arrays.stream(a.get("sizes", suite.sizes).split(",")).map(Units::parseBytes).toList();
        int trials = a.getInt("trials", suite.trials);
        int warmup = a.getInt("warmup", 1);
        long timeoutMs = a.getLong("timeout", suite.timeoutSec) * 1000;
        long seed = a.getLong("seed", 42);
        double rateFraction = Double.parseDouble(a.get("rate-fraction", "0.95"));
        boolean skipAfterTimeout = !a.flag("no-skip");
        Path outRoot = Path.of(a.get("out", "results"));
        Config base = a.config();
        boolean explicitRate = a.has("rate");
        a.rejectUnknown();

        Path dir = outRoot.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Path work = dir.resolve("work");
        Files.createDirectories(work);
        Map<String, Scenario> scMap = new LinkedHashMap<>();
        for (Scenario s : scenarios) scMap.put(s.name(), s);
        writeEnvironment(dir.resolve("environment.txt"), base, protocols, scenarios, sizes, trials, warmup,
                timeoutMs, seed, rateFraction, explicitRate);

        Map<Long, Path> files = new LinkedHashMap<>();
        Map<Long, String> hashes = new HashMap<>();
        for (long size : sizes) {
            Path f = work.resolve("src-" + size + ".bin");
            generate(f, size, seed ^ size);
            files.put(size, f);
            hashes.put(size, TrialRunner.sha256(f));
        }

        if (warmup > 0) {
            long wsize = Math.min(4L << 20, Collections.min(sizes));
            Path wf = work.resolve("warmup.bin");
            generate(wf, wsize, 1);
            String wh = TrialRunner.sha256(wf);
            System.out.printf("Warm-up (%d run(s) per protocol on loopback, %s)...%n", warmup, Units.bytes(wsize));
            Scenario lo = Scenario.BUILT_IN.get("loopback");
            for (int i = 0; i < warmup; i++)
                for (Protocol p : protocols) {
                    Config c = base.copy();
                    if (c.udpRateBps == 0) c.udpRateBps = 1_000_000_000L;
                    TrialRunner.run(p, lo, c, wf, wh, wsize, -1, 1, 30_000, work);
                }
        }

        int total = scenarios.size() * sizes.size() * trials * protocols.size(), done = 0;
        List<TrialResult> results = new ArrayList<>();
        Set<String> skip = new HashSet<>();
        long t0 = System.nanoTime();
        try (PrintWriter raw = new PrintWriter(Files.newBufferedWriter(dir.resolve("raw.csv"), StandardCharsets.UTF_8))) {
            raw.println(TrialResult.HEADER);
            for (Scenario sc : scenarios) {
                System.out.println("\n=== " + sc.summary());
                for (long size : sizes) {
                    for (int t = 1; t <= trials; t++) {
                        List<Protocol> order = new ArrayList<>(protocols);
                        Collections.shuffle(order, new Random(seed * 1_000_003L + t));
                        for (Protocol p : order) {
                            done++;
                            String key = sc.name() + "|" + size + "|" + p.name();
                            TrialResult r;
                            if (skip.contains(key)) {
                                r = skipped(sc, p, size, t);
                            } else {
                                Config c = base.copy();
                                if (!explicitRate && sc.emulated() && sc.rateBps() > 0)
                                    c.udpRateBps = (long) (sc.rateBps() * rateFraction);
                                System.gc();
                                r = TrialRunner.run(p, sc, c, files.get(size), hashes.get(size), size, t,
                                        seed * 7919 + t, timeoutMs, work);
                                if (r.status().equals("TIMEOUT") && skipAfterTimeout) skip.add(key);
                            }
                            results.add(r);
                            raw.println(r.csv());
                            raw.flush();
                            progress(done, total, t0, r);
                        }
                    }
                }
            }
        }
        Summary.write(results, scMap, dir);
        for (Path f : files.values()) TrialRunner.delete(f);
        TrialRunner.delete(work.resolve("warmup.bin"));
        try {
            Files.deleteIfExists(work);
        } catch (IOException ignored) {
        }
        System.out.println("\nResults written to " + dir.toAbsolutePath());
        System.out.println("  raw.csv      every trial");
        System.out.println("  summary.md   ranked tables per scenario/size");
        System.out.println("  summary.csv  aggregated statistics (input for scripts/plot_results.py)");
        System.out.println("  summary.tex  IEEE-style LaTeX tables");
    }

    /** Splits on commas, but keeps "custom:a=1,b=2" together (tokens containing '=' join the previous one). */
    static List<String> splitScenarios(String spec) {
        List<String> out = new ArrayList<>();
        for (String raw : spec.split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.contains("=") && !t.startsWith("custom:") && !out.isEmpty() && out.getLast().startsWith("custom:"))
                out.set(out.size() - 1, out.getLast() + "," + t);
            else out.add(t);
        }
        return out;
    }

    private static TrialResult skipped(Scenario sc, Protocol p, long size, int t) {
        return new TrialResult(sc.name(), p.name(), size, t, "SKIPPED", Double.NaN, Double.NaN, Double.NaN, 0, false,
                Double.NaN, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                !sc.emulated() || p.transport() != Protocol.Transport.TCP || sc.loss().meanLoss() == 0, 0,
                "skipped: an earlier trial in this cell timed out");
    }

    private static void progress(int done, int total, long t0, TrialResult r) {
        double el = (System.nanoTime() - t0) / 1e9;
        double eta = el / done * (total - done);
        System.out.printf(Locale.ROOT, "[%4d/%d eta %5.0fs] %-10s %8s %-13s #%-2d %-10s %9s Mb/s %9s s  ovh %6s  retx %6s%%%s%n",
                done, total, eta, r.scenario(), Units.bytes(r.fileBytes()), r.protocol(), r.trial(), r.status(),
                fmt(r.goodputMbps(), 2), fmt(r.durationMs() / 1000, 3), fmt(r.overhead(), 3),
                fmt(r.dataPackets() == 0 ? 0 : 100.0 * r.retransPackets() / r.dataPackets(), 2),
                r.error() == null || r.error().isEmpty() ? "" : "  (" + r.error() + ")");
    }

    private static String fmt(double v, int d) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%." + d + "f", v);
    }

    /** Deterministic incompressible content, so results do not depend on compression anywhere. */
    public static void generate(Path f, long size, long seed) throws IOException {
        SplittableRandom rnd = new SplittableRandom(seed);
        byte[] buf = new byte[1 << 20];
        try (OutputStream out = Files.newOutputStream(f)) {
            for (long left = size; left > 0; ) {
                for (int i = 0; i < buf.length; i += 8) {
                    long v = rnd.nextLong();
                    for (int j = 0; j < 8; j++) buf[i + j] = (byte) (v >>> (8 * j));
                }
                int n = (int) Math.min(buf.length, left);
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    private static void writeEnvironment(Path f, Config cfg, List<Protocol> ps, List<Scenario> scs, List<Long> sizes,
                                         int trials, int warmup, long timeoutMs, long seed, double rateFraction,
                                         boolean explicitRate) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(f, StandardCharsets.UTF_8))) {
            Runtime rt = Runtime.getRuntime();
            w.println("date: " + LocalDateTime.now());
            w.println("java: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version"));
            w.println("os: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
            w.println("cpus: " + rt.availableProcessors() + ", max heap: " + (rt.maxMemory() >> 20) + " MiB");
            w.println("jvm args: " + ManagementFactory.getRuntimeMXBean().getInputArguments());
            w.println("trials: " + trials + ", warmup: " + warmup + ", timeout ms: " + timeoutMs + ", seed: " + seed);
            w.println("udp pacing: " + (explicitRate ? cfg.udpRateBps + " bit/s (explicit)" : rateFraction + " x link rate (emulated), unpaced otherwise"));
            w.println("config: " + cfg);
            w.println("sizes: " + sizes.stream().map(Units::bytes).toList());
            w.println("protocols:");
            for (Protocol p : ps) w.println("  " + p.name() + " [" + p.transport() + "] " + p.description());
            w.println("scenarios:");
            for (Scenario s : scs) w.println("  " + s.summary());
        }
    }
}
