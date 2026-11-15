package com.yashgamerx.bench;

import java.util.Arrays;

/** Descriptive statistics with Student-t 95% confidence intervals. */
public record Stats(int n, double mean, double sd, double ci95, double median, double min, double max) {
    private static final double[] T95 = {Double.NaN, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306,
            2.262, 2.228, 2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074,
            2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};

    public static Stats of(double[] raw) {
        double[] v = Arrays.stream(raw).filter(d -> !Double.isNaN(d)).sorted().toArray();
        int n = v.length;
        if (n == 0) return new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        double mean = Arrays.stream(v).average().orElse(Double.NaN);
        double ss = 0;
        for (double d : v) ss += (d - mean) * (d - mean);
        double sd = n > 1 ? Math.sqrt(ss / (n - 1)) : 0;
        double ci = n > 1 ? tCritical(n - 1) * sd / Math.sqrt(n) : 0;
        double median = n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
        return new Stats(n, mean, sd, ci, median, v[0], v[n - 1]);
    }

    /** Two-sided 95% critical value of Student's t distribution. */
    public static double tCritical(int df) {
        if (df < T95.length) return T95[df];
        if (df <= 60) return 2.042 - (df - 30) * (2.042 - 2.000) / 30.0;
        if (df <= 120) return 2.000 - (df - 60) * (2.000 - 1.980) / 60.0;
        return 1.960;
    }
}
