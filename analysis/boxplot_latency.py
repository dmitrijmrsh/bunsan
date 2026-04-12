#!/usr/bin/env python3
"""
Сравнение latency по стратегиям: сгруппированный bar chart перцентилей.
Показывает p50, p95, p99 для каждой стратегии балансировки.

Использование:
  python boxplot_latency.py --input combined_results.csv --output boxplot_latency.png
"""
import argparse
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np
import pandas as pd

STRATEGY_ORDER  = ["round-robin","random","weighted-response-time","least-connections","adaptive"]
STRATEGY_LABELS = {"round-robin":"Round\nRobin","random":"Random","weighted-response-time":"Weighted\nResponse\nTime","least-connections":"Least\nConnections","adaptive":"Adaptive"}
COLORS = ["#4878CF","#D65F5F","#B47CC7"]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input",  default="combined_results.csv")
    parser.add_argument("--output", default="boxplot_latency.png")
    parser.add_argument("--dpi",    type=int, default=150)
    args = parser.parse_args()

    df = pd.read_csv(args.input)
    present = [s for s in STRATEGY_ORDER if s in df["strategy"].values]
    df = df[df["strategy"].isin(present)].copy()
    df["strategy"] = pd.Categorical(df["strategy"], categories=present, ordered=True)
    df = df.sort_values("strategy").reset_index(drop=True)

    x = np.arange(len(present))
    width = 0.25
    labels = [STRATEGY_LABELS.get(s, s) for s in present]

    fig, ax = plt.subplots(figsize=(10, 6))
    for i, (col, lbl) in enumerate(zip(["p50_ms","p95_ms","p99_ms"], ["p50","p95","p99"])):
        bars = ax.bar(x + (i-1)*width, df[col], width, label=lbl,
                      color=COLORS[i], alpha=0.85, edgecolor="white")
        for bar in bars:
            h = bar.get_height()
            ax.text(bar.get_x()+bar.get_width()/2, h+0.5, f"{h:.0f}",
                    ha="center", va="bottom", fontsize=8)

    ax.set_xlabel("Стратегия балансировки", fontsize=12)
    ax.set_ylabel("Response time (мс)", fontsize=12)
    ax.set_title("Latency по стратегиям балансировки (p50 / p95 / p99)", fontsize=14)
    ax.set_xticks(x); ax.set_xticklabels(labels, fontsize=10)
    ax.yaxis.set_minor_locator(ticker.AutoMinorLocator())
    ax.grid(axis="y", linestyle="--", alpha=0.5); ax.legend(fontsize=10)
    plt.tight_layout()
    plt.savefig(args.output, dpi=args.dpi, bbox_inches="tight")
    print(f"Сохранено: {args.output}")

if __name__ == "__main__":
    main()
