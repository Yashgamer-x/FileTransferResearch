package com.yashgamerx.bench;

import com.yashgamerx.emu.Scenario;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/** Aggregates raw trials into summary.csv, summary.md and IEEE-ready LaTeX tables. */
public final class Summary {
    private Summary() {}

    public record Row(String scenario, long fileBytes, String protocol, int trials, int ok, int measured,
                      Stats goodput, Stats duration, double overhead, double retransPct, double deliveredPct,
                      double utilizationPct, boolean lossApplies, String statuses) {}

    public static List<Row> aggregate(List<TrialResult> results, Map<String, Scenario> scenarios) {
        Map<List<Object>, List<TrialResult>> groups = results.stream().collect(Collectors.groupingBy(
                r -> List.of(r.scenario(), r.fileBytes(), r.protocol()), LinkedHashMap::new, Collectors.toList()));
        List<Row> rows = new ArrayList<>();
        for (List<TrialResult> g : groups.values()) {
            TrialResult first = g.getFirst();
            List<TrialResult> m = g.stream().filter(TrialResult::measured).toList();
            Stats gp = Stats.of(m.stream().mapToDouble(TrialResult::goodputMbps).toArray());
            Stats du = Stats.of(m.stream().mapToDouble(r -> r.durationMs() / 1000.0).toArray());
            Scenario sc = scenarios.get(first.scenario());
            double util = sc != null && sc.emulated() && sc.rateBps() > 0 ? 100.0 * gp.mean() * 1e6 / sc.rateBps() : Double.NaN;
            String statuses = g.stream().collect(Collectors.groupingBy(TrialResult::status, TreeMap::new, Collectors.counting()))
                    .entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" "));
            rows.add(new Row(first.scenario(), first.fileBytes(), first.protocol(), g.size(),
                    (int) g.stream().filter(TrialResult::ok).count(), m.size(), gp, du,
                    mean(m, TrialResult::overhead),
                    mean(m, r -> r.dataPackets() == 0 ? 0 : 100.0 * r.retransPackets() / r.dataPackets()),
                    mean(m, TrialResult::deliveredPct), util, first.tcpLossEmulated(), statuses));
        }
        // stable order: scenario as run, then file size, then protocol registry order
        List<String> scOrder = rows.stream().map(Row::scenario).distinct().toList();
        List<String> pOrder = new ArrayList<>(com.yashgamerx.proto.Protocols.ALL.keySet());
        rows.sort(Comparator.comparingInt((Row r) -> scOrder.indexOf(r.scenario))
                .thenComparingLong(Row::fileBytes)
                .thenComparingInt(r -> pOrder.contains(r.protocol) ? pOrder.indexOf(r.protocol) : Integer.MAX_VALUE));
        return rows;
    }

    private static double mean(List<TrialResult> l, ToDoubleFunction<TrialResult> f) {
        return l.stream().mapToDouble(f).filter(d -> !Double.isNaN(d)).average().orElse(Double.NaN);
    }

    public static void write(List<TrialResult> results, Map<String, Scenario> scenarios, Path dir) throws IOException {
        List<Row> rows = aggregate(results, scenarios);
        writeCsv(rows, dir.resolve("summary.csv"));
        writeMarkdown(rows, scenarios, dir.resolve("summary.md"));
        writeLatex(rows, scenarios, dir.resolve("summary.tex"));
    }

    static void writeCsv(List<Row> rows, Path file) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("scenario,file_bytes,protocol,trials,ok,measured,goodput_mean_mbps,goodput_sd,goodput_ci95,"
                    + "goodput_median,goodput_min,goodput_max,time_mean_s,time_ci95_s,overhead_ratio,retrans_pct,"
                    + "delivered_pct,link_utilization_pct,loss_applies_to_protocol,statuses");
            for (Row r : rows) {
                w.println(String.join(",", r.scenario, Long.toString(r.fileBytes), r.protocol,
                        Integer.toString(r.trials), Integer.toString(r.ok), Integer.toString(r.measured),
                        TrialResult.f(r.goodput.mean()), TrialResult.f(r.goodput.sd()), TrialResult.f(r.goodput.ci95()),
                        TrialResult.f(r.goodput.median()), TrialResult.f(r.goodput.min()), TrialResult.f(r.goodput.max()),
                        TrialResult.f(r.duration.mean()), TrialResult.f(r.duration.ci95()), TrialResult.f(r.overhead),
                        TrialResult.f(r.retransPct), TrialResult.f(r.deliveredPct), TrialResult.f(r.utilizationPct),
                        Boolean.toString(r.lossApplies), r.statuses));
            }
        }
    }

    static void writeMarkdown(List<Row> rows, Map<String, Scenario> scenarios, Path file) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("# Benchmark summary\n");
            w.println("Goodput = file bytes x 8 / completion time (receiver has the whole, verified file). "
                    + "Mean ± 95% CI (Student t) over successful trials. Overhead = all bytes sent by both ends / file size.");
            w.println("† = built-in emulator does not apply packet loss to kernel TCP; treat as an upper bound "
                    + "(use netem/clumsy for TCP-under-loss).\n");
            Map<List<Object>, List<Row>> byCell = rows.stream().collect(Collectors.groupingBy(
                    r -> List.of(r.scenario, r.fileBytes), LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<List<Object>, List<Row>> e : byCell.entrySet()) {
                Scenario sc = scenarios.get((String) e.getKey().get(0));
                w.printf("## %s — %s%n%n", sc != null ? sc.summary() : e.getKey().get(0), Units.bytes((Long) e.getKey().get(1)));
                w.println("| Rank | Protocol | Success | Goodput (Mb/s) | Time (s) | Utilization | Overhead | Retrans % | Delivered % | Outcomes |");
                w.println("|---:|---|---:|---:|---:|---:|---:|---:|---:|---|");
                List<Row> sorted = new ArrayList<>(e.getValue());
                sorted.sort(Comparator.comparingDouble((Row r) -> r.measured > 0 && r.ok == r.trials ? -r.goodput.mean() : 1e12 - r.goodput.mean()));
                int rank = 1;
                for (Row r : sorted) {
                    w.printf(Locale.ROOT, "| %d | %s%s | %d/%d | %s | %s | %s | %s | %s | %s | %s |%n", rank++, r.protocol,
                            r.lossApplies ? "" : " †", r.ok, r.trials, pm(r.goodput, 2), pm(r.duration, 3),
                            Double.isNaN(r.utilizationPct) ? "–" : String.format(Locale.ROOT, "%.1f%%", r.utilizationPct),
                            num(r.overhead, 3), num(r.retransPct, 2), num(r.deliveredPct, 2), r.statuses);
                }
                w.println();
            }
        }
    }

    /** One table per file size: protocols x scenarios, mean goodput, best per column in bold. */
    static void writeLatex(List<Row> rows, Map<String, Scenario> scenarios, Path file) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("% Auto-generated by FileTransferProtocolResearch. Requires no extra packages (IEEEtran-compatible).");
            List<Long> sizes = rows.stream().map(Row::fileBytes).distinct().sorted().toList();
            List<String> scs = rows.stream().map(Row::scenario).distinct().toList();
            List<String> order = new ArrayList<>(com.yashgamerx.proto.Protocols.ALL.keySet());
            List<String> protos = rows.stream().map(Row::protocol).distinct()
                    .sorted(Comparator.comparingInt(p -> order.contains(p) ? order.indexOf(p) : Integer.MAX_VALUE)).toList();
            for (long size : sizes) {
                Map<String, Map<String, Row>> cell = new HashMap<>();
                for (Row r : rows) if (r.fileBytes == size) cell.computeIfAbsent(r.protocol, k -> new HashMap<>()).put(r.scenario, r);
                w.println("\\begin{table*}[!t]");
                w.println("\\centering");
                w.printf("\\caption{Mean goodput (Mb/s) $\\pm$ 95\\%% CI, %s file. Best reliable protocol per scenario in bold (excluding dagger-marked rows); "
                        + "--- = no successful trial.}%n", Units.bytes(size));
                w.printf("\\label{tab:goodput-%s}%n", Units.bytes(size).replace(" ", ""));
                w.println("\\footnotesize");
                w.println("\\begin{tabular}{l" + "c".repeat(scs.size()) + "}");
                w.println("\\hline");
                w.println("\\textbf{Protocol} & " + scs.stream().map(s -> "\\textbf{" + tex(s) + "}").collect(Collectors.joining(" & ")) + " \\\\");
                w.println("\\hline");
                Map<String, String> best = new HashMap<>();
                for (String s : scs) {
                    protos.stream().map(p -> cell.getOrDefault(p, Map.of()).get(s))
                            .filter(r -> r != null && r.ok == r.trials && r.ok > 0 && r.lossApplies && !r.protocol.equals("udp-blast"))
                            .max(Comparator.comparingDouble(r -> r.goodput.mean()))
                            .ifPresent(r -> best.put(s, r.protocol));
                }
                for (String p : protos) {
                    StringBuilder sb = new StringBuilder(tex(p));
                    for (String s : scs) {
                        Row r = cell.getOrDefault(p, Map.of()).get(s);
                        sb.append(" & ");
                        if (r == null || r.measured == 0) {
                            sb.append("---");
                            continue;
                        }
                        String v = String.format(Locale.ROOT, "%.1f $\\pm$ %.1f", r.goodput.mean(), r.goodput.ci95());
                        if (p.equals(best.get(s))) v = "\\textbf{" + v + "}";
                        if (!r.lossApplies) v += "$^\\dagger$";
                        if (r.ok < r.trials && !p.equals("udp-blast")) v += String.format("$^{%d/%d}$", r.ok, r.trials);
                        sb.append(v);
                    }
                    w.println(sb + " \\\\");
                }
                w.println("\\hline");
                w.println("\\multicolumn{" + (scs.size() + 1) + "}{l}{\\scriptsize $^\\dagger$ loss not applied to kernel TCP by the in-process emulator; "
                        + "superscript $k/n$ = successful trials.}\\\\");
                w.println("\\end{tabular}");
                w.println("\\end{table*}");
                w.println();
            }
        }
    }

    private static String pm(Stats s, int digits) {
        if (s.n() == 0) return "–";
        return String.format(Locale.ROOT, "%." + digits + "f ± %." + digits + "f", s.mean(), s.ci95());
    }

    private static String num(double v, int digits) {
        return Double.isNaN(v) ? "–" : String.format(Locale.ROOT, "%." + digits + "f", v);
    }

    private static String tex(String s) {
        return s.replace("_", "\\_").replace("%", "\\%");
    }
}
