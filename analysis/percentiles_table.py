#!/usr/bin/env python3
"""
Сводная таблица метрик по каждой стратегии.
Экспортирует: percentiles_table.png и percentiles_table.csv.

Использование:
  python percentiles_table.py --input combined_results.csv
"""
import argparse
from pathlib import Path
import matplotlib.pyplot as plt
import pandas as pd

STRATEGY_ORDER  = ["round-robin","random","weighted-response-time","least-connections","adaptive"]
STRATEGY_LABELS = {"round-robin":"Round Robin","random":"Random","weighted-response-time":"Weighted Response Time","least-connections":"Least Connections","adaptive":"Adaptive"}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input",  default="combined_results.csv")
    parser.add_argument("--output-png", default="percentiles_table.png")
    parser.add_argument("--output-csv", default="percentiles_table.csv")
    parser.add_argument("--dpi",    type=int, default=150)
    args = parser.parse_args()

    df = pd.read_csv(args.input)
    present = [s for s in STRATEGY_ORDER if s in df["strategy"].values]
    df = df[df["strategy"].isin(present)].copy()
    df["strategy"] = pd.Categorical(df["strategy"], categories=present, ordered=True)
    df = df.sort_values("strategy").reset_index(drop=True)
    df["error_rate_pct"] = (df["ko"] / df["count"] * 100).round(2)

    table_df = pd.DataFrame({
        "Стратегия":      [STRATEGY_LABELS.get(s, s) for s in df["strategy"]],
        "p50 (мс)":       df["p50_ms"].astype(int),
        "p75 (мс)":       df["p75_ms"].astype(int),
        "p95 (мс)":       df["p95_ms"].astype(int),
        "p99 (мс)":       df["p99_ms"].astype(int),
        "max (мс)":       df["max_ms"].astype(int),
        "mean (мс)":      df["mean_ms"].astype(int),
        "σ (мс)":         df["std_ms"].astype(int),
        "Ошибки (%)":     df["error_rate_pct"],
        "RPS":            df["rps"].round(2),
        "Запросов":       df["count"].astype(int),
    })

    table_df.to_csv(args.output_csv, index=False)
    print(f"CSV сохранён: {args.output_csv}")
    print(table_df.to_string(index=False))

    # PNG-таблица
    fig, ax = plt.subplots(figsize=(14, 1.2 + 0.5*len(table_df)))
    ax.axis("off")
    col_labels = list(table_df.columns)
    cell_data  = table_df.values.tolist()
    tbl = ax.table(cellText=cell_data, colLabels=col_labels,
                   cellLoc="center", loc="center")
    tbl.auto_set_font_size(False)
    tbl.set_fontsize(9)
    tbl.scale(1, 1.6)

    for (r, c), cell in tbl.get_celld().items():
        if r == 0:
            cell.set_facecolor("#4878CF"); cell.set_text_props(color="white", fontweight="bold")
        elif r % 2 == 0:
            cell.set_facecolor("#EEF2FF")
        else:
            cell.set_facecolor("white")
        cell.set_edgecolor("#CCCCCC")

    ax.set_title("Сводная таблица метрик балансировки", fontsize=13, pad=10)
    plt.tight_layout()
    plt.savefig(args.output_png, dpi=args.dpi, bbox_inches="tight")
    print(f"PNG сохранён: {args.output_png}")

if __name__ == "__main__":
    main()
