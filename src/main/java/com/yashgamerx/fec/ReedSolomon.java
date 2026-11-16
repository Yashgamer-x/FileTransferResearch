package com.yashgamerx.fec;

/**
 * Systematic Reed-Solomon erasure code over GF(2^8) with a Cauchy generator matrix
 * (Blömer et al. 1995; Rizzo 1997; Plank 2006). k data shards plus m parity shards; any k of the
 * k+m shards reconstruct the data. Parity row j, column i has coefficient 1 / (x_j + y_i) with
 * x_j = j and y_i = m + i, so every square submatrix of the generator is invertible (k + m <= 256).
 */
public final class ReedSolomon {
    private static final int POLY = 0x11d;
    private static final byte[] EXP = new byte[512];
    private static final int[] LOG = new int[256];
    private static final byte[][] MUL = new byte[256][256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = (byte) x;
            LOG[x] = i;
            x <<= 1;
            if (x >= 256) x ^= POLY;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
        for (int a = 1; a < 256; a++)
            for (int b = 1; b < 256; b++) MUL[a][b] = EXP[LOG[a] + LOG[b]];
    }

    static int mul(int a, int b) {
        return MUL[a & 0xff][b & 0xff] & 0xff;
    }

    static int inv(int a) {
        if (a == 0) throw new ArithmeticException("inverse of 0 in GF(256)");
        return EXP[255 - LOG[a]] & 0xff;
    }

    private final int m;

    public ReedSolomon(int parityShards) {
        if (parityShards < 1 || parityShards > 128) throw new IllegalArgumentException("parity shards 1..128");
        this.m = parityShards;
    }

    public int parityShards() {
        return m;
    }

    /** Coefficient of data shard i in parity shard j. */
    int coef(int j, int i) {
        return inv(j ^ (m + i));
    }

    /** dst += c * src over {@code len} bytes. */
    static void mulAdd(byte[] dst, byte[] src, int c, int len) {
        if (c == 0) return;
        byte[] row = MUL[c];
        for (int b = 0; b < len; b++) dst[b] ^= row[src[b] & 0xff];
    }

    /** Computes parity shard j from k data shards of {@code len} bytes (shorter shards zero-padded). */
    public void encode(byte[][] data, int k, int j, byte[] parityOut, int len) {
        java.util.Arrays.fill(parityOut, 0, len, (byte) 0);
        for (int i = 0; i < k; i++) mulAdd(parityOut, data[i], coef(j, i), len);
    }

    /**
     * Rebuilds missing data shards. {@code shards[0..k)} are data shards (null = missing),
     * {@code parity[j]} parity shards (null = missing). Needs at least k shards in total.
     * Missing data shards are allocated and filled in place; returns false if too few shards.
     */
    public boolean decode(byte[][] shards, int k, byte[][] parity, int len) {
        int[] rows = new int[k];      // which shard supplies each equation: i < k data, k + j parity j
        int have = 0, missing = 0;
        for (int i = 0; i < k; i++) if (shards[i] != null) rows[have++] = i; else missing++;
        if (missing == 0) return true;
        for (int j = 0; j < parity.length && have < k; j++) if (parity[j] != null) rows[have++] = k + j;
        if (have < k) return false;

        // A (k x k): row r expresses shard rows[r] as a combination of the data shards
        int[][] a = new int[k][2 * k];
        for (int r = 0; r < k; r++) {
            if (rows[r] < k) a[r][rows[r]] = 1;
            else for (int i = 0; i < k; i++) a[r][i] = coef(rows[r] - k, i);
            a[r][k + r] = 1;
        }
        // Gauss-Jordan inversion
        for (int c = 0; c < k; c++) {
            int p = c;
            while (a[p][c] == 0) p++;
            int[] t = a[p]; a[p] = a[c]; a[c] = t;
            int iv = inv(a[c][c]);
            for (int x = 0; x < 2 * k; x++) a[c][x] = mul(a[c][x], iv);
            for (int r = 0; r < k; r++) {
                if (r == c || a[r][c] == 0) continue;
                int f = a[r][c];
                for (int x = 0; x < 2 * k; x++) a[r][x] ^= mul(f, a[c][x]);
            }
        }
        // data_i = sum_r inv[i][r] * shard(rows[r]); only compute the missing ones
        byte[][] src = new byte[k][];
        for (int r = 0; r < k; r++) src[r] = rows[r] < k ? shards[rows[r]] : parity[rows[r] - k];
        for (int i = 0; i < k; i++) {
            if (shards[i] != null) continue;
            byte[] out = new byte[len];
            for (int r = 0; r < k; r++) mulAdd(out, src[r], a[i][k + r], len);
            shards[i] = out;
        }
        return true;
    }
}
