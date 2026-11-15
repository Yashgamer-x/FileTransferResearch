package com.yashgamerx;

import com.yashgamerx.bench.BenchRunner;
import com.yashgamerx.bench.TrialResult;
import com.yashgamerx.bench.TrialRunner;
import com.yashgamerx.core.Config;
import com.yashgamerx.core.Protocol;
import com.yashgamerx.emu.Scenario;
import com.yashgamerx.proto.Protocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Every reliable protocol must deliver a byte-identical file over clean and lossy emulated links. */
class ProtocolIntegrityTest {
    static final long SIZE = 512 * 1024 + 123; // deliberately not a multiple of the chunk size
    @TempDir
    static Path dir;
    static Path file;
    static String hash;

    @BeforeAll
    static void setUp() throws Exception {
        file = dir.resolve("src.bin");
        BenchRunner.generate(file, SIZE, 7);
        hash = TrialRunner.sha256(file);
    }

    static Stream<String> protocols() {
        return Protocols.ALL.keySet().stream();
    }

    private static TrialResult run(String proto, String scenario) throws Exception {
        Protocol p = Protocols.get(proto);
        Scenario sc = Scenario.parse(scenario);
        Config cfg = new Config();
        cfg.udpRateBps = sc.emulated() ? (long) (sc.rateBps() * 0.95) : 500_000_000L;
        return TrialRunner.run(p, sc, cfg, file, hash, SIZE, 1, 99, 60_000, dir);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    void loopback(String proto) throws Exception {
        TrialResult r = run(proto, "loopback");
        if (Protocols.get(proto).reliable()) assertEquals("OK", r.status(), r.toString());
        else assertTrue(r.measured(), r.toString());
    }

    @ParameterizedTest
    @MethodSource("protocols")
    void lossyLink(String proto) throws Exception {
        TrialResult r = run(proto, "custom:name=test,rate=50,rtt=10,loss=0.03");
        if (Protocols.get(proto).reliable()) {
            assertEquals("OK", r.status(), r.toString());
            assertTrue(r.integrity());
        } else {
            assertEquals("INCOMPLETE", r.status(), "3% loss should leave holes: " + r);
            assertTrue(r.deliveredPct() > 80 && r.deliveredPct() < 100, r.toString());
        }
    }

    @ParameterizedTest
    @MethodSource("protocols")
    void burstLossWithJitter(String proto) throws Exception {
        TrialResult r = run(proto, "custom:name=burst,rate=50,rtt=10,jitter=2,loss=0.02,burst=5");
        if (Protocols.get(proto).reliable()) assertEquals("OK", r.status(), r.toString());
    }
}
