#!/usr/bin/env python3
"""
Сравнение пропускной способности (RPS) по стратегиям.

Использование:
  python throughput_over_time.py --input combined_results.csv --output throughput_over_time.png
"""
import argparse
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import pandas as pd

STRATEGY_ORDER  = ["round-robin","random","weighted-response-time","least-connections","adaptive"]
STRATEGY_LABELS = {"round-robin":"Round\nRobin","random":"Random","weighted-response-time":"Weighted\nResponse\nTime","least-connections":"Least\nConnections","adaptive":"Adaptive"}
PALETTE = ["#4878CF","#6ACC65","#D65F5F","#B47CC7","#C4AD66"]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input",  default="combined_results.csv")
    parser.add_argument("--output", default="throughput_over_time.png")
    parser.add_argument("--dpi",    type=int, default=150)
    args = parser.parse_args()

    df = pd.read_csv(args.input)
    present = [s for s in STRATEGY_ORDER if s in df["strategy"].values]
    df = df[df["strategy"].isin(present)].copy()
    df["strategy"] = pd.Categorical(df["strategy"], categories=present, ordered=True)
    df = df.sort_values("strategy").reset_index(drop=True)

    labels = [STRATEGY_LABELS.get(s, s) for s in df["strategy"]]
    colors = [PALETTE[i % len(PALETTE)] for i in range(len(df))]

    fig, ax = plt.subplots(figsize=(9, 5))
    bars = ax.bar(labels, df["rps"], color=colors, edgecolor="white", width=0.5)
    for bar, val in zip(bars, df["rps"]):
        ax.text(bar.get_x()+bar.get_width()/2, bar.get_height()+0.1,
                f"{val:.2f}", ha="center", va="bottom", fontsize=9)

    ax.set_xlabel("Стратегия балансировки", fontsize=12)
    ax.set_ylabel("Среднее RPS (запросов/сек)", fontsize=12)
    ax.set_title("Пропускная способность по стратегиям", fontsize=14)
    ax.yaxis.set_minor_locator(ticker.AutoMinorLocator())
    ax.grid(axis="y", linestyle="--", alpha=0.5)
    plt.tight_layout()
    plt.savefig(args.output, dpi=args.dpi, bbox_inches="tight")
    print(f"Сохранено: {args.output}")

if __name__ == "__main__":
    main()
