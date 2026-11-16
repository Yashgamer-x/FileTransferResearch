package com.yashgamerx.proto;

import com.yashgamerx.core.Protocol;

import java.util.*;

/** Registry of every protocol implementation, in the order they are reported. */
public final class Protocols {
    private Protocols() {}

    public static final Map<String, Protocol> ALL = new LinkedHashMap<>();

    static {
        for (Protocol p : List.of(
                new TcpStreamProtocol(),
                new TcpNioProtocol(),
                new TcpParallelProtocol(),
                new UdpBlastProtocol(),
                new RbudpProtocol(RbudpProtocol.Fec.NONE),
                new RbudpProtocol(RbudpProtocol.Fec.XOR),
                new RbudpProtocol(RbudpProtocol.Fec.RS),
                new LtFountainProtocol(),
                new TsunamiProtocol(),
                new ArqProtocol(ArqProtocol.Mode.SAW),
                new ArqProtocol(ArqProtocol.Mode.GBN),
                new ArqProtocol(ArqProtocol.Mode.SR),
                new ArqProtocol(ArqProtocol.Mode.SR_AIMD),
                new ArqProtocol(ArqProtocol.Mode.SR_HYBLA),
                new ArqProtocol(ArqProtocol.Mode.SR_LEDBAT))) {
            ALL.put(p.name(), p);
        }
    }

    public static Protocol get(String name) {
        Protocol p = ALL.get(name);
        if (p == null) throw new IllegalArgumentException("unknown protocol '" + name + "'; known: " + ALL.keySet());
        return p;
    }

    /** "all", or a comma-separated list; a leading '-' excludes, e.g. "all,-udp-saw". */
    public static List<Protocol> select(String spec) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String raw : spec.split(",")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            if (s.equals("all")) names.addAll(ALL.keySet());
            else if (s.startsWith("-")) names.remove(s.substring(1));
            else names.add(get(s).name());
        }
        return names.stream().map(Protocols::get).toList();
    }
}
