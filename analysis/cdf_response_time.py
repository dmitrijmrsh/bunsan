#!/usr/bin/env python3
"""
Приближённый CDF времени ответа по стратегиям (из перцентилей stats.js).
Ось X — response time (мс), ось Y — доля запросов (0-1).

Использование:
  python cdf_response_time.py --input combined_results.csv --output cdf_response_time.png
"""
import argparse
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np
import pandas as pd

STRATEGY_ORDER  = ["round-robin","random","weighted-response-time","least-connections","adaptive"]
STRATEGY_LABELS = {"round-robin":"Round Robin (baseline)","random":"Random (baseline)","weighted-response-time":"Weighted Response Time","least-connections":"Least Connections","adaptive":"Adaptive"}
COLORS      = ["#4878CF","#6ACC65","#D65F5F","#B47CC7","#C4AD66"]
LINESTYLES  = ["-","--","-.",(0,(3,1,1,1)),":"]

def make_cdf_points(row):
    """Строит приближённый CDF из 7 контрольных точек."""
    pts = [
        (0,      0.00),
        (row.p50_ms, 0.50),
        (row.p75_ms, 0.75),
        (row.p95_ms, 0.95),
        (row.p99_ms, 0.99),
        (row.max_ms, 1.00),
    ]
    # Убираем дубликаты и гарантируем монотонность
    xs, ys = zip(*sorted(set(pts)))
    return np.array(xs, dtype=float), np.array(ys, dtype=float)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input",  default="combined_results.csv")
    parser.add_argument("--output", default="cdf_response_time.png")
    parser.add_argument("--max-ms", type=int, default=None)
    parser.add_argument("--dpi",    type=int, default=150)
    args = parser.parse_args()

    df = pd.read_csv(args.input)
    present = [s for s in STRATEGY_ORDER if s in df["strategy"].values]
    df = df[df["strategy"].isin(present)].copy()
    df["strategy"] = pd.Categorical(df["strategy"], categories=present, ordered=True)
    df = df.sort_values("strategy").reset_index(drop=True)

    fig, ax = plt.subplots(figsize=(10, 6))
    for i, (_, row) in enumerate(df.iterrows()):
        xs, ys = make_cdf_points(row)
        ax.plot(xs, ys,
                color=COLORS[i % len(COLORS)],
                linestyle=LINESTYLES[i % len(LINESTYLES)],
                linewidth=2,
                label=STRATEGY_LABELS.get(row.strategy, row.strategy))
        for x, y, pct in [(row.p50_ms,0.5,"p50"),(row.p95_ms,0.95,"p95"),(row.p99_ms,0.99,"p99")]:
            ax.annotate(f"{pct}\n{x:.0f}мс", xy=(x, y),
                        fontsize=7, ha="left", va="bottom",
                        color=COLORS[i % len(COLORS)])

    ax.axhline(0.95, color="gray", linestyle=":", linewidth=1, alpha=0.7)
    ax.axhline(0.99, color="gray", linestyle=":", linewidth=1, alpha=0.7)
    ax.set_xlabel("Response time (мс)", fontsize=12)
    ax.set_ylabel("Доля запросов", fontsize=12)
    ax.set_title("CDF времени ответа по стратегиям (приближение по перцентилям)", fontsize=13)
    ax.set_ylim(0, 1.05)
    if args.max_ms:
        ax.set_xlim(0, args.max_ms)
    ax.yaxis.set_major_formatter(ticker.PercentFormatter(xmax=1))
    ax.grid(linestyle="--", alpha=0.4); ax.legend(fontsize=10)
    plt.tight_layout()
    plt.savefig(args.output, dpi=args.dpi, bbox_inches="tight")
    print(f"Сохранено: {args.output}")

if __name__ == "__main__":
    main()
