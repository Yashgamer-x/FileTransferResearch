package com.yashgamerx.emu;

import java.util.*;

/**
 * A network condition. {@code emulated=false} means traffic goes straight over the OS
 * network stack: either plain loopback, or an "external" scenario where the user shapes the
 * real interface with netem / clumsy and only supplies a label.
 */
public record Scenario(String name, boolean emulated, long rateBps, double rttMs, double jitterMs,
                       LossModel loss, long queueBytes, boolean lossOnReverse, String description) {

    public static final Map<String, Scenario> BUILT_IN = new LinkedHashMap<>();

    static {
        add(new Scenario("loopback", false, 0, 0, 0, LossModel.none(), 0, false,
                "Direct OS loopback, no emulation (host-limited upper bound)"));
        add(link("lan", 1_000, 1, 0, new LossModel.Bernoulli(0), "Data-centre LAN: 1 Gb/s, 1 ms RTT, no loss"));
        add(link("metro", 100, 10, 0, new LossModel.Bernoulli(0.0001), "Metro: 100 Mb/s, 10 ms RTT, 0.01% loss"));
        add(link("wan", 100, 50, 0, new LossModel.Bernoulli(0.001), "Continental WAN: 100 Mb/s, 50 ms RTT, 0.1% loss"));
        add(link("lossy-wan", 100, 50, 0, new LossModel.Bernoulli(0.01), "Lossy WAN: 100 Mb/s, 50 ms RTT, 1% loss"));
        add(link("burst-wan", 100, 50, 0, new LossModel.GilbertElliott(0.001, 0.1, 0, 1),
                "WAN with Gilbert-Elliott burst loss: 100 Mb/s, 50 ms RTT, ~1% loss in bursts of ~10"));
        add(link("high-loss", 50, 100, 0, new LossModel.Bernoulli(0.05), "Degraded path: 50 Mb/s, 100 ms RTT, 5% loss"));
        add(link("mobile", 50, 40, 5, new LossModel.Bernoulli(0.02), "Mobile/wireless: 50 Mb/s, 40 ms RTT, 5 ms jitter, 2% loss"));
        add(link("satellite", 20, 600, 0, new LossModel.Bernoulli(0.005), "GEO satellite: 20 Mb/s, 600 ms RTT, 0.5% loss"));
    }

    private static void add(Scenario s) {
        BUILT_IN.put(s.name, s);
    }

    /** Symmetric emulated link with a one-BDP drop-tail buffer (minimum 64 KiB). */
    public static Scenario link(String name, double mbps, double rttMs, double jitterMs, LossModel loss, String desc) {
        long rate = (long) (mbps * 1e6);
        long bdp = (long) (rate / 8.0 * rttMs / 1000.0);
        return new Scenario(name, true, rate, rttMs, jitterMs, loss, Math.max(64 * 1024, bdp), true, desc);
    }

    /**
     * Parses a built-in name, {@code external:<label>} (no emulation, conditions applied outside the JVM),
     * or {@code custom:name=x,rate=100,rtt=50,loss=0.01,jitter=0,queue=65536,burst=10,fwdonly=true}
     * (rate in Mb/s, rtt/jitter in ms, loss as a fraction, queue in bytes, burst = mean burst length).
     */
    public static Scenario parse(String spec) {
        if (BUILT_IN.containsKey(spec)) return BUILT_IN.get(spec);
        if (spec.startsWith("external:")) {
            String label = spec.substring("external:".length());
            return new Scenario(label, false, 0, 0, 0, LossModel.none(), 0, false,
                    "External network (shaped outside the JVM, e.g. netem/clumsy)");
        }
        if (spec.startsWith("custom:")) {
            Map<String, String> kv = new HashMap<>();
            for (String part : spec.substring(7).split(",")) {
                String[] p = part.split("=", 2);
                if (p.length == 2) kv.put(p[0].trim().toLowerCase(Locale.ROOT), p[1].trim());
            }
            double mbps = Double.parseDouble(kv.getOrDefault("rate", "100"));
            double rtt = Double.parseDouble(kv.getOrDefault("rtt", "50"));
            double jitter = Double.parseDouble(kv.getOrDefault("jitter", "0"));
            double loss = Double.parseDouble(kv.getOrDefault("loss", "0"));
            double burst = Double.parseDouble(kv.getOrDefault("burst", "1"));
            LossModel lm = burst > 1 && loss > 0
                    ? new LossModel.GilbertElliott(loss / burst / (1 - loss), 1 / burst, 0, 1)
                    : new LossModel.Bernoulli(loss);
            String name = kv.getOrDefault("name", "custom-" + (int) mbps + "M-" + (int) rtt + "ms-" + loss);
            Scenario s = link(name, mbps, rtt, jitter, lm, "Custom: " + spec.substring(7));
            long queue = kv.containsKey("queue") ? Long.parseLong(kv.get("queue")) : s.queueBytes;
            boolean fwdOnly = Boolean.parseBoolean(kv.getOrDefault("fwdonly", "false"));
            return new Scenario(name, true, s.rateBps, rtt, jitter, lm, queue, !fwdOnly, s.description);
        }
        throw new IllegalArgumentException("unknown scenario '" + spec + "'; known: " + BUILT_IN.keySet()
                + ", external:<label>, custom:rate=..,rtt=..,loss=..");
    }

    public String summary() {
        if (!emulated) return name + " (" + description + ")";
        return String.format(Locale.ROOT, "%s: %.0f Mb/s, RTT %.1f ms, jitter %.1f ms, loss %.3f%% %s, queue %d B%s",
                name, rateBps / 1e6, rttMs, jitterMs, loss.meanLoss() * 100,
                loss instanceof LossModel.GilbertElliott ? "(burst)" : "", queueBytes,
                lossOnReverse ? ", loss both directions" : ", loss forward only");
    }
}
