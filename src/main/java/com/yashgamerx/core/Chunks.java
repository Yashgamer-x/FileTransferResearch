package com.yashgamerx.core;

public final class Chunks {
    private Chunks() {}

    public static int count(long size, int chunk) {
        long n = (size + chunk - 1) / chunk;
        if (n > Integer.MAX_VALUE) throw new IllegalArgumentException("file too large for chunk size");
        return (int) n;
    }

    public static int length(long size, int chunk, int idx) {
        return (int) Math.min(chunk, size - (long) idx * chunk);
    }
}
