package com.yashgamerx.bench;

import java.util.Locale;

/** One row of raw.csv: a single transfer of one file with one protocol under one scenario. */
public record TrialResult(
        String scenario, String protocol, long fileBytes, int trial, String status,
        double durationMs, double senderMs, double goodputMbps, double deliveredPct, boolean integrity,
        double overhead, long senderBytes, long receiverBytes, long dataPackets, long retransPackets,
        long fecPackets, long fecRecovered, long rounds, long rtoTimeouts, long fastRetransmits, long duplicates,
        long fwdQueueDrops, long fwdLosses, long revQueueDrops, long revLosses, boolean tcpLossEmulated,
        double finalRateMbps, String error) {

    public static final String HEADER = String.join(",",
            "scenario", "protocol", "file_bytes", "trial", "status", "duration_ms", "sender_ms", "goodput_mbps",
            "delivered_pct", "integrity_ok", "overhead_ratio", "sender_bytes", "receiver_bytes", "data_pkts",
            "retrans_pkts", "fec_pkts", "fec_recovered", "rounds", "rto_timeouts", "fast_retransmits", "dup_pkts",
            "fwd_queue_drops", "fwd_losses", "rev_queue_drops", "rev_losses", "loss_applies_to_protocol",
            "final_rate_mbps", "error");

    public boolean ok() {
        return status.equals("OK");
    }

    /** Usable for throughput statistics: correct transfers, plus best-effort transfers that finished. */
    public boolean measured() {
        return status.equals("OK") || status.equals("INCOMPLETE");
    }

    public String csv() {
        return String.join(",", scenario, protocol, Long.toString(fileBytes), Integer.toString(trial), status,
                f(durationMs), f(senderMs), f(goodputMbps), f(deliveredPct), Boolean.toString(integrity), f(overhead),
                Long.toString(senderBytes), Long.toString(receiverBytes), Long.toString(dataPackets),
                Long.toString(retransPackets), Long.toString(fecPackets), Long.toString(fecRecovered),
                Long.toString(rounds), Long.toString(rtoTimeouts), Long.toString(fastRetransmits),
                Long.toString(duplicates), Long.toString(fwdQueueDrops), Long.toString(fwdLosses),
                Long.toString(revQueueDrops), Long.toString(revLosses), Boolean.toString(tcpLossEmulated),
                f(finalRateMbps), '"' + (error == null ? "" : error.replace('"', '\'').replace('\n', ' ')) + '"');
    }

    /** Parses a line written by {@link #csv()} (the last field is the only quoted one). */
    public static TrialResult parse(String line) {
        int q = line.indexOf(",\"");
        String error = q >= 0 ? line.substring(q + 2, line.length() - 1) : "";
        String[] c = (q >= 0 ? line.substring(0, q) : line).split(",", -1);
        int i = 0;
        return new TrialResult(c[i++], c[i++], Long.parseLong(c[i++]), Integer.parseInt(c[i++]), c[i++],
                d(c[i++]), d(c[i++]), d(c[i++]), d(c[i++]), Boolean.parseBoolean(c[i++]), d(c[i++]),
                l(c[i++]), l(c[i++]), l(c[i++]), l(c[i++]), l(c[i++]), l(c[i++]), l(c[i++]), l(c[i++]),
                l(c[i++]), l(c[i++]), l(c[i++]), l(c[i++]), l(c[i++]), l(c[i++]), Boolean.parseBoolean(c[i++]),
                d(c[i]), error);
    }

    private static double d(String s) {
        return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
    }

    private static long l(String s) {
        return s.isEmpty() ? 0 : Long.parseLong(s);
    }

    static String f(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.4f", v);
    }
}
