#!/usr/bin/env python3
"""效果类指标测量（v5.6.0 / FR-v5.6-65 / W56-53）。

为什么单独做
------------
既有门禁能判「有没有越线」，但判不了「这一版到底好用不好用」：
`tools/perf_longlist.py` / `perf_runchain.py` 只产出**未测量**框架，
`perf_sample.py` 采的是四类预算场景，不含「Top8 页面成功率」与「创建写路径 P95」。
效果指标长期只有一句「未测」，于是每次评审都只能说「理论上更快了」。

三项指标与测法
--------------
  top8   八个主页面各自的首屏只读接口，各打 N 次，统计 2xx 比例（成功率）。
         选这八个是因为它们就是用户进入八个主菜单后第一批要等的请求。
  create 创建工程（写路径含审计 + 租户守卫 + 唯一约束）连续 N 次，取 p50/p95；
         每次创建后立刻删除，不留脏数据。
  fps    10000 行长列表滚动帧率——需要真实浏览器，由 scripts/e2e-smoke.mjs 测量；
         本工具不伪造，测不到就写「未测」。

口径纪律
--------
* 没有样本就写 `status=未测`，绝不填 0 当成达标（这是本版被点名要修的假绿）。
* 每个文件带 `capturedAt` / `sampleSize` / `environment`，缺一个都不算证据。
* 成功率只按 HTTP 状态判，不猜业务码；2xx 即算成功。

用法
----
    python3 tools/effect_metrics.py                  # 三项都尝试
    python3 tools/effect_metrics.py --samples 30
    python3 tools/effect_metrics.py --only top8

退出码：0 = 已产生证据（含「未测」）；2 = 登录/连不上，无法产出任何证据。
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "docs" / "evidence" / "v5.6.0" / "effect-metrics"
ENV_FILE = Path(os.environ.get("ROBOT_ENV_FILE", Path.home() / ".config/robot-autotest/env"))
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

PROJECT_ID = int(os.environ.get("EFFECT_PROJECT_ID", "95"))

# 八个主页面各自的首屏只读接口（页面 → 请求）。路径取自 docs/contracts/openapi.json，
# 不写猜测路径：猜错会得到 404，然后被记成「成功率低」，实际是用错了 URL。
TOP8 = [
    ("workbench", "/api/projects/cards"),
    ("cases", f"/api/projects/{PROJECT_ID}/cases?page=1&size=20"),
    ("runs", f"/api/execution/runs?projectId={PROJECT_ID}&page=1&size=20"),
    ("reports", f"/api/reports?projectId={PROJECT_ID}&page=1&size=20"),
    ("assets", f"/api/projects/{PROJECT_ID}/devices"),
    ("audit", "/api/audit?page=1&size=20"),
    ("case-library", "/api/global/cases?page=1&size=20"),
    ("about", "/api/system/build-info"),
]


def env_value(key: str) -> str | None:
    if os.environ.get(key):
        return os.environ[key]
    try:
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, _, value = line.partition("=")
            if name.strip() == key:
                return value.strip().strip('"').strip("'")
    except OSError:
        return None
    return None


def base_url() -> str:
    return env_value("EFFECT_BASE_URL") or "http://127.0.0.1:8001"


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def machine() -> dict:
    return {
        "host": platform.node(),
        "platform": f"{platform.system()} {platform.release()}",
        "python": platform.python_version(),
    }


def http(method: str, url: str, token: str | None, body: dict | None = None,
         timeout: float = 30.0) -> tuple[int, float, str]:
    """返回 (状态码, 毫秒, 响应文本)。连不上时状态码 0（便于统计成失败而不是抛栈）。"""
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    started = time.perf_counter()
    try:
        with _OPENER.open(request, timeout=timeout) as response:
            text = response.read().decode("utf-8", "replace")
            return response.status, (time.perf_counter() - started) * 1000.0, text
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8", "replace") if exc.fp else ""
        return exc.code, (time.perf_counter() - started) * 1000.0, text
    except (urllib.error.URLError, TimeoutError, OSError):
        return 0, (time.perf_counter() - started) * 1000.0, ""


def login() -> tuple[str, str]:
    password = env_value("QA_E2E_PASSWORD")
    user = env_value("QA_E2E_USER") or "qa-e2e-admin"
    if not password:
        raise SystemExit("缺少 QA_E2E_PASSWORD（~/.config/robot-autotest/env 或环境变量）")
    code, _, text = http("POST", f"{base_url()}/api/auth/login", None,
                         {"username": user, "password": password})
    if code != 200:
        raise SystemExit(f"登录失败：HTTP {code} {text[:200]}")
    token = (json.loads(text).get("data") or {}).get("token") or ""
    if not token:
        raise SystemExit("登录响应里没有 token")
    return token, user


def write(name: str, payload: dict) -> Path:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    path = OUT_DIR / name
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"  已写 {path.relative_to(ROOT)}")
    return path


def measure_top8(token: str, samples: int) -> dict:
    rows = []
    total_ok = 0
    total_all = 0
    for page, path in TOP8:
        codes = []
        latencies = []
        for _ in range(samples):
            code, ms, _text = http("GET", f"{base_url()}{path}", token)
            codes.append(code)
            latencies.append(ms)
        ok = sum(1 for c in codes if 200 <= c < 300)
        total_ok += ok
        total_all += len(codes)
        rows.append({
            "page": page,
            "path": path,
            "samples": len(codes),
            "ok": ok,
            "successRate": round(ok / len(codes), 4) if codes else None,
            "p50Ms": round(statistics.median(latencies), 1) if latencies else None,
            "p95Ms": round(sorted(latencies)[max(0, int(len(latencies) * 0.95) - 1)], 1)
            if latencies else None,
            "codes": sorted(set(codes)),
        })
    return {
        "metric": "Top8 主页面首屏接口成功率",
        "status": "已测" if total_all else "未测",
        "capturedAt": now_iso(),
        "sampleSize": total_all,
        "environment": {"base": base_url(), "machine": machine(), "projectId": PROJECT_ID},
        "overallSuccessRate": round(total_ok / total_all, 4) if total_all else None,
        "pages": rows,
        "note": "成功率只按 HTTP 2xx 判，不猜业务码；code=0 表示连接失败。",
    }


def measure_create(token: str, samples: int) -> dict:
    latencies = []
    failures = []
    leftovers = []
    stamp = datetime.now().strftime("%H%M%S")
    for index in range(samples):
        body = {
            "name": f"w56-effect-{stamp}-{index}",
            "code": f"W56E{stamp}{index:02d}",
            "kind": "standard",
            "description": "效果指标测量用临时工程（测完即删）",
        }
        code, ms, text = http("POST", f"{base_url()}/api/projects", token, body)
        if code == 200:
            latencies.append(ms)
            created = (json.loads(text).get("data") or {}).get("id")
            if created:
                del_code, _, _ = http("DELETE", f"{base_url()}/api/projects/{created}", token)
                if del_code != 200:
                    leftovers.append({"id": created, "deleteStatus": del_code})
        else:
            failures.append({"index": index, "status": code, "message": text[:160]})
    latencies.sort()

    def pct(p: float) -> float | None:
        if not latencies:
            return None
        idx = min(len(latencies) - 1, max(0, int(round(len(latencies) * p)) - 1))
        return round(latencies[idx], 1)

    payload = {
        "metric": "创建工程 P95（写路径：审计 + 租户守卫 + 唯一约束）",
        "status": "已测" if latencies else "未测",
        "capturedAt": now_iso(),
        "sampleSize": len(latencies),
        "environment": {"base": base_url(), "machine": machine()},
        "p50Ms": round(statistics.median(latencies), 1) if latencies else None,
        "p95Ms": pct(0.95),
        "minMs": round(latencies[0], 1) if latencies else None,
        "maxMs": round(latencies[-1], 1) if latencies else None,
        "failedSamples": failures,
        "leftovers": leftovers,
        "note": "每次创建后立即删除，不留脏数据；leftovers 非空即表示有残留待手工清理。"
                "预算里的 runChain.createP95 需要起真实执行，本文件不等价于它。",
    }
    if not latencies:
        payload["reason"] = "全部创建请求失败，没有可用样本（不填 0 冒充达标）"
    return payload


def measure_fps(fps_from: Path | None = None) -> dict:
    """fps 必须由真实浏览器给。这里只判「能不能测」，不编数字。

    `fps_from` 指向 scripts/e2e-smoke.mjs 产出的证据（里面带 fps 段）：
    有观测值就照抄并注明样本规模，但**目标场景（10000 行）没测到就仍然是「未测」**——
    20 行/页 的 60fps 不能拿来宣称长列表达标。
    """
    if fps_from is not None and fps_from.is_file():
        try:
            observed = (json.loads(fps_from.read_text(encoding="utf-8")) or {}).get("fps") or {}
        except (ValueError, OSError):
            observed = {}
        if observed.get("fps") is not None:
            return {
                "metric": "10000 行长列表滚动 minFps",
                "status": "未测",
                "capturedAt": now_iso(),
                "sampleSize": observed.get("rows") or 0,
                "environment": {"machine": machine(), "source": str(fps_from)},
                "minFps": None,
                "observed": {
                    "rows": observed.get("rows"),
                    "fps": observed.get("fps"),
                    "worstFrameMs": observed.get("worstFrameMs"),
                    "frames": observed.get("frames"),
                },
                "reason": "目标场景是 10000 行，本机列表只有 "
                          f"{observed.get('rows')} 行/页；该观测不能代表 10000 行，"
                          "按「无样本写未测」的口径不记为达标",
            }
    cdp = env_value("CDP_BASE") or "http://127.0.0.1:9222"
    reachable = False
    try:
        code, _, _ = http("GET", f"{cdp}/json/version", None, timeout=3)
        reachable = code == 200
    except SystemExit:
        raise
    except Exception:
        reachable = False
    if reachable:
        return {
            "metric": "10000 行长列表滚动 minFps",
            "status": "未测",
            "capturedAt": now_iso(),
            "sampleSize": 0,
            "environment": {"cdp": cdp, "machine": machine()},
            "minFps": None,
            "reason": "CDP 可用，但帧率由 scripts/e2e-smoke.mjs 在页面内采样；"
                      "请跑该脚本，本文件不重复实现滚动采样",
        }
    return {
        "metric": "10000 行长列表滚动 minFps",
        "status": "未测",
        "capturedAt": now_iso(),
        "sampleSize": 0,
        "environment": {"cdp": cdp, "machine": machine()},
        "minFps": None,
        "reason": f"CDP 不可达（{cdp}）：没有真实浏览器就没有帧率，写 0 等于伪造",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="v5.6.0 效果类指标测量")
    parser.add_argument("--samples", type=int, default=20, help="每项样本数（默认 20）")
    parser.add_argument("--only", choices=["top8", "create", "fps", "all"], default="all")
    parser.add_argument("--fps-from", type=Path,
                        help="scripts/e2e-smoke.mjs 产出的证据文件，用于取 fps 观测值")
    args = parser.parse_args()
    stamp = datetime.now().strftime("%Y%m%d")

    if args.only == "fps":
        write(f"longlist-fps-{stamp}.json", measure_fps(args.fps_from))
        return 0

    try:
        token, _user = login()
    except SystemExit:
        raise
    print(f"[effect] 已登录，base={base_url()} samples={args.samples}")

    if args.only in ("top8", "all"):
        payload = measure_top8(token, max(1, args.samples))
        print(f"[effect] Top8 成功率 {payload['overallSuccessRate']}（{payload['sampleSize']} 次）")
        write(f"top8-success-{stamp}.json", payload)

    if args.only in ("create", "all"):
        payload = measure_create(token, max(1, args.samples))
        print(f"[effect] 创建 P95 {payload['p95Ms']}ms（{payload['sampleSize']} 个样本）")
        write(f"create-p95-{stamp}.json", payload)

    if args.only == "all":
        write(f"longlist-fps-{stamp}.json", measure_fps(args.fps_from))

    return 0


if __name__ == "__main__":
    sys.exit(main())
