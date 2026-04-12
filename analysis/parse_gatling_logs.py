#!/usr/bin/env python3
"""
Конвертер Gatling stats.js → CSV (Gatling 3.13+).

Gatling 3.13+ использует бинарный формат simulation.log.
Этот скрипт читает агрегированную статистику из stats.js —
файла, который Gatling автоматически генерирует в HTML-отчёте.

CSV-формат (одна строка на стратегию):
  strategy,count,ok,ko,min_ms,max_ms,mean_ms,std_ms,p50_ms,p75_ms,p95_ms,p99_ms,rps

Использование:
  python parse_gatling_logs.py \\
      --logs-dir ../load-tests/results \\
      --output combined_results.csv
"""

import argparse
import re
import sys
from pathlib import Path

import pandas as pd

STRATEGIES = [
    "round-robin",
    "random",
    "weighted-response-time",
    "least-connections",
    "adaptive",
]


def detect_strategy(stats_path: Path) -> str:
    """Определяет стратегию по имени директории результатов."""
    name = stats_path.parent.parent.name.lower().replace("-", "").replace("_", "")
    for s in STRATEGIES:
        if s.replace("-", "") in name:
            return s
    return "unknown"


def extract_total(content: str, key: str) -> float | None:
    """Извлекает числовое значение поля 'total' для заданного ключа."""
    m = re.search(
        rf'"{re.escape(key)}"\s*:\s*\{{[^}}]*"total"\s*:\s*"([^"]+)"',
        content,
    )
    if m and m.group(1) != "-":
        try:
            return float(m.group(1))
        except ValueError:
            pass
    return None


def parse_stats_js(stats_path: Path, strategy: str) -> dict:
    """Парсит один stats.js и возвращает словарь метрик для стратегии."""
    content = stats_path.read_text(encoding="utf-8", errors="replace")

    # Берём только глобальную секцию (до блока contents:)
    global_section = re.split(r"\bcontents\s*:", content)[0]

    row: dict = {"strategy": strategy}

    field_map = {
        "count":   "numberOfRequests",
        "p50_ms":  "percentiles1",
        "p75_ms":  "percentiles2",
        "p95_ms":  "percentiles3",
        "p99_ms":  "percentiles4",
        "mean_ms": "meanResponseTime",
        "std_ms":  "standardDeviation",
        "min_ms":  "minResponseTime",
        "max_ms":  "maxResponseTime",
    }
    for col, key in field_map.items():
        row[col] = extract_total(global_section, key)

    # ok / ko из numberOfRequests
    ok_m = re.search(
        r'"numberOfRequests"\s*:\s*\{[^}]*"ok"\s*:\s*"([^"]+)"', global_section
    )
    ko_m = re.search(
        r'"numberOfRequests"\s*:\s*\{[^}]*"ko"\s*:\s*"([^"]+)"', global_section
    )
    row["ok"] = float(ok_m.group(1)) if ok_m and ok_m.group(1) != "-" else 0.0
    row["ko"] = float(ko_m.group(1)) if ko_m and ko_m.group(1) != "-" else 0.0

    # RPS
    rps_m = re.search(
        r'"meanNumberOfRequestsPerSecond"\s*:\s*\{[^}]*"total"\s*:\s*"([^"]+)"',
        global_section,
    )
    row["rps"] = float(rps_m.group(1)) if rps_m and rps_m.group(1) != "-" else 0.0

    return row


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Конвертер Gatling stats.js → сводный CSV"
    )
    parser.add_argument(
        "--logs-dir",
        default="../load-tests/results",
        help="Директория с результатами Gatling (содержит поддиректории симуляций)",
    )
    parser.add_argument(
        "--output",
        default="combined_results.csv",
        help="Путь к выходному CSV-файлу",
    )
    args = parser.parse_args()

    logs_dir = Path(args.logs_dir)
    if not logs_dir.exists():
        print(f"ERROR: директория не найдена: {logs_dir}", file=sys.stderr)
        sys.exit(1)

    stats_files = sorted(logs_dir.rglob("stats.js"))
    if not stats_files:
        print(f"ERROR: stats.js файлы не найдены в {logs_dir}", file=sys.stderr)
        sys.exit(1)

    print(f"Найдено {len(stats_files)} stats.js файл(ов):")
    rows = []
    for stats_path in stats_files:
        strategy = detect_strategy(stats_path)
        row = parse_stats_js(stats_path, strategy)
        rows.append(row)
        print(
            f"  {stats_path.parent.parent.name} → strategy={strategy}: "
            f"{int(row.get('count') or 0):,} запросов, "
            f"p50={row.get('p50_ms')} мс, "
            f"p95={row.get('p95_ms')} мс, "
            f"ошибок={int(row.get('ko') or 0)}"
        )

    columns = [
        "strategy", "count", "ok", "ko",
        "min_ms", "max_ms", "mean_ms", "std_ms",
        "p50_ms", "p75_ms", "p95_ms", "p99_ms", "rps",
    ]
    df = pd.DataFrame(rows, columns=columns)
    df.to_csv(args.output, index=False)

    print(f"\nСохранено {len(df)} строк в {args.output}")
    print(
        df[["strategy", "count", "p50_ms", "p95_ms", "p99_ms", "rps"]].to_string(
            index=False
        )
    )


if __name__ == "__main__":
    main()
