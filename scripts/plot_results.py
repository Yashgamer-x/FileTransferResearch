#!/usr/bin/env python3
"""
IEEE-style figures from a benchmark run.

    pip install matplotlib
    python scripts/plot_results.py results/<run-dir>

Produces, in <run-dir>/figures/ (PDF for LaTeX + PNG for previews):
  goodput_<size>.pdf      grouped bars: mean goodput +/- 95% CI per protocol, one group per scenario
  overhead_<size>.pdf     bytes on the wire / file size
  time_vs_size_<sc>.pdf   completion time vs file size (log-log), one line per protocol
  goodput_box_<sc>.pdf    per-trial goodput distribution (box plot) from raw.csv
and pairwise_tests.csv:   Welch t-test p-values (two-sided) between protocols in each cell,
                          for "A is significantly faster than B" statements in the paper.
Only the standard library and matplotlib are required.
"""
import csv
import math
import os
import sys
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

IEEE_COL = 3.5   # single-column width, inches
IEEE_DBL = 7.16  # double-column width
plt.rcParams.update({
    "font.family": "serif", "font.size": 8, "axes.labelsize": 8, "legend.fontsize": 6.5,
    "xtick.labelsize": 7, "ytick.labelsize": 7, "axes.grid": True, "grid.alpha": 0.3,
    "savefig.bbox": "tight", "savefig.dpi": 300, "pdf.fonttype": 42,
})


def fnum(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return float("nan")


def human(b):
    b = int(b)
    for unit, sh in (("GiB", 30), ("MiB", 20), ("KiB", 10)):
        if b >= 1 << sh and b % (1 << sh) == 0:
            return f"{b >> sh}{unit}"
    return f"{b}B"


def save(fig, out, name):
    for ext in ("pdf", "png"):
        fig.savefig(os.path.join(out, f"{name}.{ext}"))
    plt.close(fig)


def welch_p(a, b):
    """Two-sided Welch t-test p-value (normal approximation of t for df>30, else Student-t via betainc)."""
    na, nb = len(a), len(b)
    if na < 2 or nb < 2:
        return float("nan")
    ma, mb = sum(a) / na, sum(b) / nb
    va = sum((x - ma) ** 2 for x in a) / (na - 1)
    vb = sum((x - mb) ** 2 for x in b) / (nb - 1)
    se = math.sqrt(va / na + vb / nb)
    if se == 0:
        return 0.0 if ma != mb else 1.0
    t = (ma - mb) / se
    df = (va / na + vb / nb) ** 2 / ((va / na) ** 2 / (na - 1) + (vb / nb) ** 2 / (nb - 1))
    x = df / (df + t * t)
    return betainc(df / 2, 0.5, x)  # = P(|T| > |t|)


def betainc(a, b, x):
    """Regularized incomplete beta I_x(a,b) by continued fraction (Numerical Recipes)."""
    if x <= 0:
        return 0.0
    if x >= 1:
        return 1.0
    lbeta = math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + a * math.log(x) + b * math.log(1 - x)
    if x < (a + 1) / (a + b + 2):
        return math.exp(lbeta) * _cf(a, b, x) / a
    return 1 - math.exp(lbeta) * _cf(b, a, 1 - x) / b


def _cf(a, b, x, it=200, eps=3e-12):
    qab, qap, qam = a + b, a + 1, a - 1
    c, d = 1.0, 1 - qab * x / qap
    d = 1 / (d if abs(d) > 1e-300 else 1e-300)
    h = d
    for m in range(1, it):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1 + aa * d
        d = 1 / (d if abs(d) > 1e-300 else 1e-300)
        c = 1 + aa / c if abs(1 + aa / c) > 1e-300 else 1e-300
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1 + aa * d
        d = 1 / (d if abs(d) > 1e-300 else 1e-300)
        c = 1 + aa / c if abs(1 + aa / c) > 1e-300 else 1e-300
        delta = d * c
        h *= delta
        if abs(delta - 1) < eps:
            break
    return h


def main(run):
    out = os.path.join(run, "figures")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(run, "summary.csv"), newline="") as f:
        summary = list(csv.DictReader(f))
    with open(os.path.join(run, "raw.csv"), newline="") as f:
        raw = list(csv.DictReader(f))

    scenarios = list(dict.fromkeys(r["scenario"] for r in summary))
    protocols = list(dict.fromkeys(r["protocol"] for r in summary))
    sizes = sorted({int(r["file_bytes"]) for r in summary})
    cell = {(r["scenario"], int(r["file_bytes"]), r["protocol"]): r for r in summary}
    cmap = plt.get_cmap("tab20")

    # 1. one panel per scenario (independent y axes), one bar per protocol, for goodput and overhead
    color = {p: cmap(i % 20) for i, p in enumerate(protocols)}
    for metric, ylabel, fname in (("goodput_mean_mbps", "Goodput (Mb/s)", "goodput"),
                                  ("overhead_ratio", "Bytes sent / file size", "overhead")):
        for size in sizes:
            cols = min(3, len(scenarios))
            rows_ = math.ceil(len(scenarios) / cols)
            fig, axes = plt.subplots(rows_, cols, figsize=(IEEE_DBL, 2.0 * rows_), squeeze=False)
            for k, sc in enumerate(scenarios):
                ax = axes[k // cols][k % cols]
                ps = [p for p in protocols if (sc, size, p) in cell and cell[(sc, size, p)]["measured"] != "0"]
                ys = [fnum(cell[(sc, size, p)][metric]) for p in ps]
                es = [fnum(cell[(sc, size, p)]["goodput_ci95"]) for p in ps] if metric == "goodput_mean_mbps" else None
                ax.bar(range(len(ps)), ys, 0.75, yerr=es, capsize=1.5, color=[color[p] for p in ps],
                       hatch=None, edgecolor="black", linewidth=0.3, error_kw={"elinewidth": 0.6})
                for i, p in enumerate(ps):  # mark rows where the emulator did not apply loss (kernel TCP)
                    if cell[(sc, size, p)]["loss_applies_to_protocol"] == "false":
                        ax.text(i, ys[i], "†", ha="center", va="bottom", fontsize=7)
                ax.set_xticks(range(len(ps)))
                ax.set_xticklabels(ps, rotation=60, ha="right", fontsize=6)
                ax.set_title(sc, fontsize=8)
                ax.set_ylim(bottom=0)
                if k % cols == 0:
                    ax.set_ylabel(ylabel)
            for k in range(len(scenarios), rows_ * cols):
                axes[k // cols][k % cols].axis("off")
            fig.tight_layout()
            save(fig, out, f"{fname}_{human(size)}")

    # 2. completion time vs size per scenario
    if len(sizes) > 1:
        for sc in scenarios:
            fig, ax = plt.subplots(figsize=(IEEE_COL, 2.4))
            for i, p in enumerate(protocols):
                pts = [(s, fnum(cell[(sc, s, p)]["time_mean_s"]), fnum(cell[(sc, s, p)]["time_ci95_s"]))
                       for s in sizes if (sc, s, p) in cell and cell[(sc, s, p)]["measured"] != "0"]
                if not pts:
                    continue
                ax.errorbar([x[0] / 2 ** 20 for x in pts], [x[1] for x in pts], yerr=[x[2] for x in pts],
                            marker="os^vD<>ph*x"[i % 11], ms=3, lw=0.8, capsize=1.5, label=p, color=cmap(i % 20))
            ax.set_xscale("log")
            ax.set_yscale("log")
            ax.set_xlabel("File size (MiB)")
            ax.set_ylabel("Completion time (s)")
            ax.set_title(sc)
            ax.legend(ncol=2, frameon=False)
            save(fig, out, f"time_vs_size_{sc}")

    # 3. per-trial distributions + pairwise Welch tests
    groups = defaultdict(list)
    for r in raw:
        if r["status"] in ("OK", "INCOMPLETE") and r["goodput_mbps"]:
            groups[(r["scenario"], int(r["file_bytes"]), r["protocol"])].append(fnum(r["goodput_mbps"]))
    for sc in scenarios:
        size = sizes[-1]
        data = [(p, groups.get((sc, size, p), [])) for p in protocols]
        data = [d for d in data if d[1]]
        if not data:
            continue
        fig, ax = plt.subplots(figsize=(IEEE_COL, 2.4))
        ax.boxplot([d[1] for d in data], tick_labels=[d[0] for d in data], showfliers=True,
                   flierprops={"markersize": 2})
        ax.set_ylabel("Goodput (Mb/s)")
        ax.set_title(f"{sc}, {human(size)}")
        plt.setp(ax.get_xticklabels(), rotation=60, ha="right")
        save(fig, out, f"goodput_box_{sc}")

    with open(os.path.join(run, "pairwise_tests.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["scenario", "file_bytes", "protocol_a", "protocol_b", "mean_a", "mean_b", "welch_p_two_sided"])
        for (sc, size) in dict.fromkeys((k[0], k[1]) for k in groups):
            ps = [p for p in protocols if groups.get((sc, size, p))]
            for i, a in enumerate(ps):
                for b in ps[i + 1:]:
                    A, B = groups[(sc, size, a)], groups[(sc, size, b)]
                    w.writerow([sc, size, a, b, f"{sum(A) / len(A):.3f}", f"{sum(B) / len(B):.3f}",
                                f"{welch_p(A, B):.4g}"])
    print(f"figures in {out}; pairwise tests in {os.path.join(run, 'pairwise_tests.csv')}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: plot_results.py <results/run-dir>")
    main(sys.argv[1])
