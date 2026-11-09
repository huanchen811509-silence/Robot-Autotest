#!/usr/bin/env python3
"""EW32-01 · 固定种子规模数据集生成器。

为什么需要它
------------
v3.2.0「快而稳」的四类预算（API / 前端 / 长列表 / 运行链）必须建立在**同一份可复现**
的数据上：换一台机器、换一天重跑，只要种子和规模相同，数据内容就必须逐字节一致。
否则「p95 从 140ms 涨到 160ms」到底是代码退化还是数据集变了，没人说得清。

本工具只做一件事：按固定种子流式写出 CSV，并落一份 manifest（行数 / 字节 / sha256 /
内容总哈希）。它不连数据库、不依赖第三方库，Windows 与 WSL 行为一致。

规模（--scale）
---------------
  small     1,000 用例 / 10,000 运行 / 100,000 日志    本地冒烟
  standard 10,000 用例 / 100,000 运行 / 1,000,000 日志 预算基线（PRD §3.2）
  large    50,000 用例 / 200,000 运行 / 2,000,000 日志 容量压测（v3.4 预演）

也可以用 --cases/--runs/--logs 单独覆盖任一维度。

产物
----
  <out>/cases.csv        id,project_id,case_key,name,status,priority,created_at,updated_at
  <out>/runs.csv         id,project_id,plan_id,status,enqueued_at,started_at,duration_ms
  <out>/logs/log-000.csv run_id,ts,level,message（按 10 万行分片，避免单文件过大）
  <out>/manifest.json    种子、规模、行数、字节、每文件 sha256、contentHash

可复现判据
----------
  同一 --seed + 同一规模，两次生成的 manifest.json 中 contentHash 必须相同；
  generatedAt / elapsedMs 属于运行元数据，不参与 contentHash。

用法
----
    python3 tools/perf_dataset.py --scale standard --out ../artifacts/perf-dataset
    python3 tools/perf_dataset.py --scale small --out .tmp/ds-a
    python3 tools/perf_dataset.py --verify --out ../artifacts/perf-dataset

退出码：0 = 成功/校验一致；1 = 校验不一致；2 = 参数或 IO 错误。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SCALES = {
    "small": {"cases": 1_000, "runs": 10_000, "logs": 100_000},
    "standard": {"cases": 10_000, "runs": 100_000, "logs": 1_000_000},
    "large": {"cases": 50_000, "runs": 200_000, "logs": 2_000_000},
}

CASE_STATUS = ("active", "disabled", "draft", "archived")
CASE_PRIORITY = ("P0", "P1", "P2", "P3")
RUN_STATUS = ("queued", "running", "passed", "failed", "canceled", "error")
LOG_LEVELS = ("INFO", "INFO", "INFO", "WARN", "ERROR", "DEBUG")
LOG_TEMPLATES = (
    "step {n} passed in {ms}ms",
    "step {n} failed: assertion mismatch at line {n}",
    "browser session {n} started",
    "waiting for selector #field-{n}",
    "retry {n} scheduled after timeout",
    "artifact uploaded: {n}.png",
    "suite {n} finished",
    "database fixture reset for shard {n}",
)


def _iso(base: datetime, seconds: int) -> str:
    return (base + timedelta(seconds=seconds)).strftime("%Y-%m-%dT%H:%M:%SZ")


def _stream(path: Path, rows, header_rows: int = 0) -> tuple[int, int, str]:
    """流式写二进制、同时算 sha256；返回 (数据行数, 字节数, sha256)。

    header_rows 只用于把 CSV 表头从行数里剔掉：manifest 的 rows 必须等于真实数据行，
    否则「1 万用例」会变成 10001，读数与 PRD 口径对不上。
    """
    digest = hashlib.sha256()
    total_bytes = 0
    count = 0
    buf: list[bytes] = []
    with path.open("wb") as fh:
        for line in rows:
            data = line.encode("utf-8")
            buf.append(data)
            total_bytes += len(data)
            count += 1
            if len(buf) >= 256:
                chunk = b"".join(buf)
                digest.update(chunk)
                fh.write(chunk)
                buf.clear()
        if buf:
            chunk = b"".join(buf)
            digest.update(chunk)
            fh.write(chunk)
    return max(0, count - header_rows), total_bytes, digest.hexdigest()


def gen_cases(project_id: int, count: int, rng: random.Random, base: datetime):
    yield "id,project_id,case_key,name,status,priority,created_at,updated_at\n"
    for i in range(1, count + 1):
        created = rng.randint(0, 86400 * 30)
        updated = created + rng.randint(0, 86400 * 7)
        status = rng.choice(CASE_STATUS)
        priority = rng.choice(CASE_PRIORITY)
        name = f"perf case {i:06d}"
        yield (f"{i},{project_id},PERF-C{i:06d},{name},{status},{priority},"
               f"{_iso(base, created)},{_iso(base, updated)}\n")


def gen_runs(project_id: int, count: int, rng: random.Random, base: datetime):
    yield "id,project_id,plan_id,status,enqueued_at,started_at,duration_ms\n"
    for i in range(1, count + 1):
        enq = rng.randint(0, 86400 * 60)
        duration = rng.randint(5_000, 45 * 60 * 1_000)
        status = rng.choices(RUN_STATUS, weights=(1, 1, 55, 35, 4, 4))[0]
        if status == "queued":
            started = ""
            duration = 0
        else:
            started = _iso(base, enq + rng.randint(1, 120))
        yield (f"{i},{project_id},{rng.randint(1, 50)},{status},"
               f"{_iso(base, enq)},{started},{duration}\n")


def gen_logs(run_count: int, total: int, rng: random.Random, base: datetime):
    """按 run 分组写入，日志时间递增；run_id 只落在 1..run_count。"""
    for i in range(total):
        run_id = i % run_count + 1
        level = rng.choice(LOG_LEVELS)
        template = rng.choice(LOG_TEMPLATES)
        n = rng.randint(1, 200)
        ms = rng.randint(0, 4_000)
        ts = base + timedelta(seconds=i // 50, milliseconds=rng.randint(0, 999))
        yield (f"{run_id},{ts.strftime('%Y-%m-%dT%H:%M:%S.')}{ts.microsecond // 1000:03d}Z,"
               f"{level},{template.format(n=n, ms=ms)}\n")


def generate(out: Path, scale: str, seed: int, project_id: int,
             cases: int, runs: int, logs: int, log_split: int) -> dict:
    started = time.perf_counter()
    rng = random.Random(seed)
    base = datetime(2026, 1, 1, tzinfo=timezone.utc)
    out.mkdir(parents=True, exist_ok=True)

    files: list[dict] = []

    cases_path = out / "cases.csv"
    rows, size, sha = _stream(cases_path, gen_cases(project_id, cases, rng, base), 1)
    files.append({"path": "cases.csv", "rows": rows, "bytes": size,
                  "headerRows": 1, "sha256": sha})

    runs_path = out / "runs.csv"
    rows, size, sha = _stream(runs_path, gen_runs(project_id, runs, rng, base), 1)
    files.append({"path": "runs.csv", "rows": rows, "bytes": size,
                  "headerRows": 1, "sha256": sha})

    logs_dir = out / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    remaining = logs
    part = 0
    while remaining > 0:
        take = min(log_split, remaining)
        # 每片独立生成器：同一随机序列，切片位置固定，保证分片边界可复现。
        rows, size, sha = _stream(
            logs_dir / f"log-{part:03d}.csv",
            gen_logs(runs, take, rng, base + timedelta(seconds=part * log_split // 50)),
        )
        files.append({"path": f"logs/log-{part:03d}.csv", "rows": rows,
                      "bytes": size, "headerRows": 0, "sha256": sha})
        remaining -= take
        part += 1

    content = hashlib.sha256()
    for item in files:
        content.update(item["sha256"].encode("utf-8"))
    manifest = {
        "version": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "seed": seed,
        "scale": scale,
        "projectId": project_id,
        "counts": {"cases": cases, "runs": runs, "logs": logs},
        "logSplit": log_split,
        "files": files,
        "contentHash": content.hexdigest(),
        "elapsedMs": round((time.perf_counter() - started) * 1000, 2),
    }
    (out / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def verify(out: Path) -> int:
    """重算现有文件哈希，与 manifest 比对（防止「数据集被改过但 manifest 没更新」）。"""
    manifest_path = out / "manifest.json"
    if not manifest_path.is_file():
        print(f"[FAIL] 缺少 manifest：{manifest_path}", file=sys.stderr)
        return 1
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    bad = 0
    for item in manifest.get("files", []):
        path = out / item["path"]
        if not path.is_file():
            print(f"[FAIL] 缺文件：{item['path']}", file=sys.stderr)
            bad += 1
            continue
        rows, size, sha = _measure(path, item.get("headerRows", 0))
        if rows != item["rows"] or size != item["bytes"] or sha != item["sha256"]:
            print(f"[FAIL] 不一致：{item['path']} 实际 rows={rows} bytes={size} "
                  f"sha={sha[:12]}…，manifest rows={item['rows']} bytes={item['bytes']} "
                  f"sha={item['sha256'][:12]}…", file=sys.stderr)
            bad += 1
        else:
            print(f"[OK]   {item['path']} rows={rows} bytes={size} sha={sha[:12]}…")
    if bad:
        print(f"[FAIL] 数据集校验失败：{bad} 个文件不一致", file=sys.stderr)
        return 1
    print(f"[PASS] 数据集校验通过：contentHash={manifest.get('contentHash', '')[:16]}…")
    return 0


def _measure(path: Path, header_rows: int = 0) -> tuple[int, int, str]:
    """只读测量：行数 / 字节 / sha256。与 _stream 的写路径分开，避免校验时截断文件。"""
    digest = hashlib.sha256()
    total_bytes = 0
    count = 0
    with path.open("rb") as fh:
        for line in fh:
            digest.update(line)
            total_bytes += len(line)
            count += 1
    return max(0, count - header_rows), total_bytes, digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="EW32-01 固定种子规模数据集生成器")
    parser.add_argument("--out", type=Path, default=ROOT / "artifacts" / "perf-dataset")
    parser.add_argument("--scale", choices=sorted(SCALES), default="small")
    parser.add_argument("--seed", type=int, default=20261108)
    parser.add_argument("--project-id", type=int, default=29)
    parser.add_argument("--cases", type=int)
    parser.add_argument("--runs", type=int)
    parser.add_argument("--logs", type=int)
    parser.add_argument("--log-split", type=int, default=100_000)
    parser.add_argument("--verify", action="store_true", help="校验现有数据集与 manifest")
    args = parser.parse_args()

    out: Path = args.out
    if args.verify:
        try:
            return verify(out)
        except OSError as exc:
            print(f"[FAIL] 读取数据集失败：{exc}", file=sys.stderr)
            return 2

    preset = SCALES[args.scale]
    cases = args.cases if args.cases is not None else preset["cases"]
    runs = args.runs if args.runs is not None else preset["runs"]
    logs = args.logs if args.logs is not None else preset["logs"]
    if min(cases, runs, logs) <= 0 or args.log_split <= 0:
        print("[FAIL] 规模必须为正整数", file=sys.stderr)
        return 2

    try:
        manifest = generate(out, args.scale, args.seed, args.project_id,
                            cases, runs, logs, args.log_split)
    except OSError as exc:
        print(f"[FAIL] 写数据集失败：{exc}", file=sys.stderr)
        return 2

    print(f"DATASET scale={args.scale} seed={args.seed} project={args.project_id}")
    for item in manifest["files"]:
        print(f"  {item['path']:<18} rows={item['rows']:>9} bytes={item['bytes']:>12} "
              f"sha256={item['sha256'][:16]}…")
    print(f"CONTENT_HASH {manifest['contentHash']}")
    print(f"DATASET_OUT {out.resolve()}")
    print(f"DATASET_RESULT PASS elapsed={manifest['elapsedMs']}ms")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
