package com.yashgamerx;

import com.yashgamerx.fec.LtCode;
import com.yashgamerx.fec.ReedSolomon;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FecTest {
    @Test
    void reedSolomonRecoversAnyMErasures() {
        Random r = new Random(1);
        int k = 16, m = 3, len = 1400;
        ReedSolomon rs = new ReedSolomon(m);
        for (int trial = 0; trial < 200; trial++) {
            int kk = trial % 2 == 0 ? k : 1 + r.nextInt(k); // also short last groups
            byte[][] data = new byte[kk][len];
            for (byte[] d : data) r.nextBytes(d);
            byte[][] parity = new byte[m][len];
            for (int j = 0; j < m; j++) rs.encode(data, kk, j, parity[j], len);
            // erase m shards chosen from data + parity
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < kk + m; i++) idx.add(i);
            Collections.shuffle(idx, r);
            byte[][] shards = new byte[kk][];
            for (int i = 0; i < kk; i++) shards[i] = data[i].clone();
            byte[][] par = new byte[m][];
            for (int j = 0; j < m; j++) par[j] = parity[j].clone();
            for (int e = 0; e < m; e++) {
                int x = idx.get(e);
                if (x < kk) shards[x] = null; else par[x - kk] = null;
            }
            assertTrue(rs.decode(shards, kk, par, len));
            for (int i = 0; i < kk; i++) assertArrayEquals(data[i], shards[i], "trial " + trial + " shard " + i);
        }
    }

    @Test
    void reedSolomonRefusesWithTooFewShards() {
        ReedSolomon rs = new ReedSolomon(2);
        byte[][] shards = new byte[4][];
        shards[0] = new byte[8];
        assertFalse(rs.decode(shards, 4, new byte[][]{new byte[8], null}, 8));
    }

    @Test
    void ltNeighboursAreDeterministicAndDistinct() {
        LtCode a = new LtCode(5000, 0.03, 0.5), b = new LtCode(5000, 0.03, 0.5);
        double avg = 0;
        for (int s = 0; s < 2000; s++) {
            int[] x = a.neighbors(s);
            assertArrayEquals(x, b.neighbors(s));
            assertEquals(x.length, Arrays.stream(x).distinct().count());
            avg += x.length;
        }
        avg /= 2000;
        assertTrue(avg > 2 && avg < 30, "mean degree " + avg);
    }

    @Test
    void ltDecodesWithModestOverheadUnderLoss() {
        // simulate the peeling decoder on indices only: count symbols needed to recover all K
        int k = 3000;
        LtCode code = new LtCode(k, 0.03, 0.5);
        Random loss = new Random(3);
        BitSet known = new BitSet(k);
        List<int[]> pending = new ArrayList<>();
        int received = 0;
        for (int seed = 0; known.cardinality() < k; seed++) {
            if (loss.nextDouble() < 0.05) continue; // 5% channel loss does not matter for a fountain
            received++;
            pending.add(code.neighbors(seed));
            boolean progress = true;
            while (progress) {
                progress = false;
                for (Iterator<int[]> it = pending.iterator(); it.hasNext(); ) {
                    int[] nb = it.next();
                    int unknown = -1, cnt = 0;
                    for (int n : nb) if (!known.get(n)) { cnt++; unknown = n; }
                    if (cnt <= 1) {
                        it.remove();
                        if (cnt == 1) { known.set(unknown); progress = true; }
                    }
                }
            }
            assertTrue(received < 2 * k, "decoder not converging");
        }
        double overhead = received / (double) k;
        assertTrue(overhead < 1.35, "LT reception overhead " + overhead);
    }
}
