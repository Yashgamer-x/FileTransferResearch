package com.yashgamerx.fec;

import java.util.BitSet;
import java.util.SplittableRandom;

/**
 * LT code (Luby, FOCS 2002) over K source symbols with the robust soliton degree distribution.
 * An encoded symbol is identified by a 32-bit seed; sender and receiver derive the same degree
 * and neighbour set from it, so only the seed travels with the payload.
 */
public final class LtCode {
    public final int k;
    private final double[] cdf; // cdf[d-1] = P(degree <= d)

    public LtCode(int k, double c, double delta) {
        if (k < 1) throw new IllegalArgumentException("k must be >= 1");
        this.k = k;
        double[] mu = new double[k + 1];
        double r = c * Math.log(k / delta) * Math.sqrt(k);
        int spike = (int) Math.max(1, Math.min(k, Math.round(k / Math.max(r, 1e-9))));
        mu[1] = 1.0 / k;                                                   // ideal soliton rho
        for (int d = 2; d <= k; d++) mu[d] = 1.0 / ((double) d * (d - 1));
        if (r > 0) {                                                       // robust part tau
            for (int d = 1; d < spike; d++) mu[d] += r / ((double) d * k);
            mu[spike] += r * Math.log(r / delta) / k;
        }
        double sum = 0;
        for (int d = 1; d <= k; d++) sum += Math.max(0, mu[d]);
        cdf = new double[k];
        double acc = 0;
        for (int d = 1; d <= k; d++) {
            acc += Math.max(0, mu[d]) / sum;
            cdf[d - 1] = acc;
        }
        cdf[k - 1] = 1.0;
    }

    /** Distinct source-symbol indices XORed into encoded symbol {@code seed}. */
    public int[] neighbors(int seed) {
        // Scramble the seed first: SplittableRandom streams seeded with an arithmetic progression
        // overlap (stream s+1 is stream s shifted by one draw), which made neighbour sets correlated.
        long z = ((long) seed << 32 ^ k) + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        SplittableRandom rnd = new SplittableRandom(z ^ (z >>> 31));
        double u = rnd.nextDouble();
        int lo = 0, hi = k - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cdf[mid] < u) lo = mid + 1;
            else hi = mid;
        }
        int d = lo + 1;
        int[] out = new int[d];
        if (d <= 32) {
            for (int n = 0; n < d; ) {
                int x = rnd.nextInt(k);
                boolean dup = false;
                for (int i = 0; i < n; i++) if (out[i] == x) { dup = true; break; }
                if (!dup) out[n++] = x;
            }
        } else {
            BitSet used = new BitSet(k);
            for (int n = 0; n < d; ) {
                int x = rnd.nextInt(k);
                if (!used.get(x)) {
                    used.set(x);
                    out[n++] = x;
                }
            }
        }
        return out;
    }
}
