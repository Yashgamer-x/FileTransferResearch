package com.yashgamerx;

import com.yashgamerx.bench.*;
import com.yashgamerx.core.Protocol;
import com.yashgamerx.emu.Scenario;
import com.yashgamerx.proto.Protocols;

import java.nio.file.Path;

/** Entry point: one executable containing every protocol, the emulator and the benchmark harness. */
public final class Main {
    private Main() {}

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0 || argv[0].equals("help") || argv[0].equals("--help")) {
            usage();
            return;
        }
        Args a = new Args(argv, 1);
        switch (argv[0]) {
            case "list" -> list();
            case "bench" -> BenchRunner.main(a);
            case "server" -> Remote.server(a);
            case "send" -> Remote.send(a);
            case "summarize" -> {
                Path raw = Path.of(a.get("raw", "results/remote.csv"));
                Path out = Path.of(a.get("out", raw.getParent() == null ? "." : raw.getParent().toString()));
                a.rejectUnknown();
                java.util.List<TrialResult> rows = java.nio.file.Files.readAllLines(raw).stream().skip(1)
                        .filter(s -> !s.isBlank()).map(TrialResult::parse).toList();
                java.nio.file.Files.createDirectories(out);
                java.util.Map<String, Scenario> sc = new java.util.LinkedHashMap<>();
                for (TrialResult r : rows)
                    sc.putIfAbsent(r.scenario(), Scenario.BUILT_IN.getOrDefault(r.scenario(), Scenario.parse("external:" + r.scenario())));
                Summary.write(rows, sc, out);
                System.out.println("summarised " + rows.size() + " trials into " + out.toAbsolutePath());
            }
            case "gen" -> {
                long size = Units.parseBytes(a.get("size", "100MB"));
                Path out = Path.of(a.get("out", "data.bin"));
                long seed = a.getLong("seed", 42);
                a.rejectUnknown();
                BenchRunner.generate(out, size, seed);
                System.out.println("wrote " + Units.bytes(size) + " of random data to " + out.toAbsolutePath());
            }
            default -> {
                System.err.println("unknown command: " + argv[0]);
                usage();
                System.exit(2);
            }
        }
        System.exit(0); // daemon helpers may still be lingering
    }

    private static void list() {
        System.out.println("Protocols:");
        for (Protocol p : Protocols.ALL.values())
            System.out.printf("  %-13s %-7s %s%n", p.name(), p.transport(), p.description());
        System.out.println("\nScenarios:");
        for (Scenario s : Scenario.BUILT_IN.values()) System.out.println("  " + s.summary());
        System.out.println("  external:<label>   no emulation; shape the real network with netem/clumsy");
        System.out.println("  custom:rate=100,rtt=50,loss=0.01,jitter=0,burst=1,queue=<bytes>,fwdonly=false,name=x");
    }

    private static void usage() {
        System.out.println("""
                File Transfer Protocol Research benchmark
                usage: java -jar ftp-bench.jar <command> [options]

                list   - show protocols and built-in network scenarios
                gen    - write a random test file:  --size 100MB --out data.bin [--seed 42]
                summarize - rebuild summary.{csv,md,tex} from any raw CSV (e.g. remote/netem runs):
                         --raw results/netem.csv [--out results/netem]
                """ + BenchRunner.HELP + "\n" + Remote.HELP + "\n" + Args.CONFIG_HELP);
    }
}
