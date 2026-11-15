package com.yashgamerx;

import com.yashgamerx.bench.Stats;
import com.yashgamerx.emu.Link;
import com.yashgamerx.emu.LossModel;
import com.yashgamerx.emu.Scenario;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorTest {
    @Test
    void bernoulliLossRate() {
        Link l = new Link(0, 0, 0, Long.MAX_VALUE, new LossModel.Bernoulli(0.01), 1);
        int lost = 0, n = 200_000;
        for (int i = 0; i < n; i++) if (l.admitDatagram(1428, 0) < 0) lost++;
        assertEquals(0.01, lost / (double) n, 0.001);
    }

    @Test
    void gilbertElliottMeanAndBurstiness() {
        LossModel ge = new LossModel.GilbertElliott(0.001, 0.1, 0, 1);
        Random r = new Random(3);
        int lost = 0, bursts = 0, n = 2_000_000;
        boolean prev = false;
        for (int i = 0; i < n; i++) {
            boolean d = ge.drop(r);
            if (d) lost++;
            if (d && !prev) bursts++;
            prev = d;
        }
        assertEquals(ge.meanLoss(), lost / (double) n, 0.002);
        assertEquals(10, lost / (double) bursts, 1.5); // mean burst length 1/pBG
    }

    @Test
    void gilbertElliottForgetsBurstAfterIdleTime() {
        LossModel ge = new LossModel.GilbertElliott(0.001, 0.1, 0, 1);
        Random r = new Random(5);
        int afterLoss = 0, lostAgain = 0;
        boolean prev = false;
        for (int i = 0; i < 3_000_000; i++) {
            boolean d = ge.drop(r, 1000); // packets 1000 slots apart: the chain has mixed in between
            if (prev) {
                afterLoss++;
                if (d) lostAgain++;
            }
            prev = d;
        }
        // independent of the previous outcome => P(loss | previous loss) ~= mean loss, not 1 - pBG
        assertEquals(ge.meanLoss(), lostAgain / (double) afterLoss, 0.01);
    }

    @Test
    void serializationAndDropTail() {
        // 8 Mb/s => 1 byte per microsecond; 10 kB queue
        Link l = new Link(8_000_000, 5_000_000, 0, 10_000, LossModel.none(), 1);
        long t1 = l.admitDatagram(1000, 0);
        assertEquals(1_000_000 + 5_000_000, t1);          // 1 ms serialisation + 5 ms propagation
        long t2 = l.admitDatagram(1000, 0);
        assertEquals(2_000_000 + 5_000_000, t2);          // queued behind the first
        int dropped = 0;
        for (int i = 0; i < 20; i++) if (l.admitDatagram(1000, 0) < 0) dropped++;
        assertTrue(dropped >= 10, "queue limit must tail-drop, dropped=" + dropped);
    }

    @Test
    void customScenarioParsing() {
        Scenario s = Scenario.parse("custom:name=x,rate=20,rtt=600,loss=0.005,jitter=3,burst=4");
        assertEquals("x", s.name());
        assertEquals(20_000_000, s.rateBps());
        assertEquals(0.005, s.loss().meanLoss(), 1e-9);
        assertTrue(s.loss() instanceof LossModel.GilbertElliott);
        assertEquals(1_500_000, s.queueBytes()); // one BDP
    }

    @Test
    void confidenceInterval() {
        Stats s = Stats.of(new double[]{10, 12, 11, 13, 9});
        assertEquals(11, s.mean(), 1e-9);
        assertEquals(Math.sqrt(2.5), s.sd(), 1e-9);
        assertEquals(2.776 * Math.sqrt(2.5) / Math.sqrt(5), s.ci95(), 1e-9);
    }
}
