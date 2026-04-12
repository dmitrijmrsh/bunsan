#!/usr/bin/env python3
"""
Сравнение ключевых метрик latency по стратегиям: mean, p95, p99.
Grouped bar chart по типу метрики.

Использование:
  python request_distribution.py --input combined_results.csv --output request_distribution.png
"""
import argparse
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np
import pandas as pd

STRATEGY_ORDER  = ["round-robin","random","weighted-response-time","least-connections","adaptive"]
STRATEGY_LABELS = {"round-robin":"Round\nRobin","random":"Random","weighted-response-time":"Weighted\nResponse\nTime","least-connections":"Least\nConnections","adaptive":"Adaptive"}
PALETTE = ["#4878CF","#6ACC65","#D65F5F","#B47CC7","#C4AD66"]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input",  default="combined_results.csv")
    parser.add_argument("--output", default="request_distribution.png")
    parser.add_argument("--dpi",    type=int, default=150)
    args = parser.parse_args()

    df = pd.read_csv(args.input)
    present = [s for s in STRATEGY_ORDER if s in df["strategy"].values]
    df = df[df["strategy"].isin(present)].copy()
    df["strategy"] = pd.Categorical(df["strategy"], categories=present, ordered=True)
    df = df.sort_values("strategy").reset_index(drop=True)

    metrics = [("mean_ms","mean"),("p95_ms","p95"),("p99_ms","p99"),("max_ms","max")]
    x = np.arange(len(metrics))
    width = 0.8 / len(present)

    fig, ax = plt.subplots(figsize=(11, 6))
    for i, (_, row) in enumerate(df.iterrows()):
        vals = [row[col] for col, _ in metrics]
        offset = (i - len(present)/2 + 0.5) * width
        bars = ax.bar(x + offset, vals, width*0.9,
                      label=STRATEGY_LABELS.get(row.strategy, row.strategy),
                      color=PALETTE[i % len(PALETTE)], alpha=0.85, edgecolor="white")
        for bar in bars:
            h = bar.get_height()
            ax.text(bar.get_x()+bar.get_width()/2, h+0.5, f"{h:.0f}",
                    ha="center", va="bottom", fontsize=7, rotation=90)

    ax.set_xlabel("Метрика", fontsize=12)
    ax.set_ylabel("Response time (мс)", fontsize=12)
    ax.set_title("Сравнение latency по метрикам и стратегиям", fontsize=14)
    ax.set_xticks(x); ax.set_xticklabels([lbl for _, lbl in metrics], fontsize=11)
    ax.yaxis.set_minor_locator(ticker.AutoMinorLocator())
    ax.grid(axis="y", linestyle="--", alpha=0.5); ax.legend(fontsize=9)
    plt.tight_layout()
    plt.savefig(args.output, dpi=args.dpi, bbox_inches="tight")
    print(f"Сохранено: {args.output}")

if __name__ == "__main__":
    main()
