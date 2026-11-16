package com.yashgamerx.bench;

import com.yashgamerx.core.Config;

import java.util.*;

/** Minimal "--key value" / "--flag" parser plus the protocol-tuning options shared by all commands. */
public final class Args {
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Set<String> used = new HashSet<>();

    public Args(String[] argv, int from) {
        for (int i = from; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--")) throw new IllegalArgumentException("unexpected argument: " + a);
            String key = a.substring(2), val = "true";
            int eq = key.indexOf('=');
            if (eq >= 0) {
                val = key.substring(eq + 1);
                key = key.substring(0, eq);
            } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                val = argv[++i];
            }
            values.put(key, val);
        }
    }

    public String get(String key, String def) {
        used.add(key);
        return values.getOrDefault(key, def);
    }

    public boolean has(String key) {
        used.add(key);
        return values.containsKey(key);
    }

    public int getInt(String key, int def) {
        return Integer.parseInt(get(key, Integer.toString(def)));
    }

    public long getLong(String key, long def) {
        return Long.parseLong(get(key, Long.toString(def)));
    }

    public boolean flag(String key) {
        return Boolean.parseBoolean(get(key, "false"));
    }

    /** Applies protocol tuning options onto a Config. */
    public Config config() {
        Config c = new Config();
        if (has("chunk")) c.chunkSize = (int) Units.parseBytes(get("chunk", null));
        if (has("rate")) c.udpRateBps = Units.parseRate(get("rate", null));
        if (has("window")) c.window = getInt("window", c.window);
        if (has("iw")) c.initialCwnd = getInt("iw", c.initialCwnd);
        if (has("min-rto")) c.minRtoMs = getLong("min-rto", c.minRtoMs);
        if (has("init-rto")) c.initialRtoMs = getLong("init-rto", c.initialRtoMs);
        if (has("tcp-buffer")) c.tcpBufferBytes = (int) Units.parseBytes(get("tcp-buffer", null));
        if (has("udp-buffer")) c.udpBufferBytes = (int) Units.parseBytes(get("udp-buffer", null));
        if (has("streams")) c.parallelStreams = getInt("streams", c.parallelStreams);
        if (has("fec")) c.fecGroup = getInt("fec", c.fecGroup);
        if (has("tsunami-update")) c.tsunamiUpdateMs = getInt("tsunami-update", c.tsunamiUpdateMs);
        if (has("tsunami-threshold")) c.tsunamiErrorThreshold = Double.parseDouble(get("tsunami-threshold", null));
        if (has("cc-window")) c.ccMaxWindow = getInt("cc-window", c.ccMaxWindow);
        if (has("hybla-rtt0")) c.hyblaRtt0Ms = getInt("hybla-rtt0", c.hyblaRtt0Ms);
        if (has("ledbat-target")) c.ledbatTargetMs = getInt("ledbat-target", c.ledbatTargetMs);
        if (has("rs-parity")) c.rsParity = getInt("rs-parity", c.rsParity);
        if (has("lt-c")) c.ltC = Double.parseDouble(get("lt-c", null));
        if (has("lt-delta")) c.ltDelta = Double.parseDouble(get("lt-delta", null));
        if (has("blast-idle")) c.blastIdleMs = getInt("blast-idle", c.blastIdleMs);
        if (has("rbudp-quiet")) c.rbudpQuietMs = getInt("rbudp-quiet", c.rbudpQuietMs);
        return c;
    }

    public void rejectUnknown() {
        for (String k : values.keySet())
            if (!used.contains(k)) throw new IllegalArgumentException("unknown option --" + k);
    }

    public static final String CONFIG_HELP = """
              Protocol tuning (all commands):
                --chunk 1400          UDP payload bytes per datagram
                --rate 95M            UDP pacing rate (bench default: --rate-fraction x link rate)
                --window 512          ARQ max window (packets)        --iw 10   initial cwnd (sr-aimd)
                --min-rto 10          min RTO ms                      --init-rto 1000  initial RTO ms
                --tcp-buffer 4M       TCP socket buffers (also the emulated TCP window)
                --udp-buffer 16M      UDP socket buffers
                --streams 4           tcp-parallel connections        --fec 16  FEC group size (rbudp-fec/rs)
                --rs-parity 2         Reed-Solomon parity per group   --lt-c 0.03 --lt-delta 0.5  LT soliton
                --hybla-rtt0 25       Hybla reference RTT ms          --ledbat-target 25  LEDBAT target ms
                --cc-window 8192      receive-window cap for sr-aimd/hybla/ledbat
                --tsunami-update 20   Tsunami feedback period ms      --tsunami-threshold 0.075
                --blast-idle 1000     udp-blast idle give-up ms       --rbudp-quiet 10  RBUDP quiet wait ms
            """;
}
