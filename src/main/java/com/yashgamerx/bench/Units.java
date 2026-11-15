package com.yashgamerx.bench;

import java.util.Locale;

public final class Units {
    private Units() {}

    /** Parses "1400", "64K", "10MB", "1.5GiB" (binary multiples). */
    public static long parseBytes(String s) {
        String t = s.trim().toUpperCase(Locale.ROOT).replace("IB", "").replace("B", "");
        long mul = 1;
        if (t.endsWith("K")) mul = 1L << 10;
        else if (t.endsWith("M")) mul = 1L << 20;
        else if (t.endsWith("G")) mul = 1L << 30;
        if (mul > 1) t = t.substring(0, t.length() - 1);
        return (long) (Double.parseDouble(t) * mul);
    }

    /** Parses "100M", "1G", "2.5g", "500k" or plain bit/s (decimal multiples, as network rates are). */
    public static long parseRate(String s) {
        String t = s.trim().toUpperCase(Locale.ROOT).replace("BPS", "").replace("BIT", "");
        double mul = 1;
        if (t.endsWith("K")) mul = 1e3;
        else if (t.endsWith("M")) mul = 1e6;
        else if (t.endsWith("G")) mul = 1e9;
        if (mul > 1) t = t.substring(0, t.length() - 1);
        return (long) (Double.parseDouble(t) * mul);
    }

    public static String bytes(long b) {
        if (b >= 1L << 30 && b % (1L << 30) == 0) return (b >> 30) + " GiB";
        if (b >= 1L << 20 && b % (1L << 20) == 0) return (b >> 20) + " MiB";
        if (b >= 1L << 10 && b % (1L << 10) == 0) return (b >> 10) + " KiB";
        return b + " B";
    }
}
