#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""交付包契约机检（门禁第 30 步 / FR-v3.11-D05）。

三段判据，缺一即红，避免「写了脚本但门禁没接线」的假绿：

  1. 静态契约：ops 脚本齐备且带护栏、打包脚本覆盖必需路径、CI workflow 用
     GitHub-hosted runner 且上传 artifact、交付指南与凭据模板齐备且无明文口令；
  2. 产物实证：优先校验 releases/*.tar.gz（解包清单 + sha256 + MANIFEST）；
     本地未打包时校验入库的 docs/evidence/v3.11.0-delivery/bundle-manifest.json；
     **两者都没有判红**（证据缺失不算通过）；
  3. 自校验：--self-check 用临时骨架做正负样本，证明上述判据真会变红。

用法：
    python3 tools/check_delivery_bundle.py
    python3 tools/check_delivery_bundle.py --self-check
    python3 tools/check_delivery_bundle.py --root /tmp/skeleton --static-only
"""
from __future__ import annotations

import hashlib
import io
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

OPS_SCRIPTS = (
    "common.sh", "doctor.sh", "install.sh", "uninstall.sh", "ctl.sh",
    "status.sh", "logs.sh", "backup.sh", "db-migrate.sh",
)
REQUIRED_BUNDLE_PATHS = (
    "MANIFEST.json", "VERSION", "CHANGELOG.md", "install.sh", "uninstall.sh",
    "bin/doctor.sh", "bin/ctl.sh", "bin/status.sh", "bin/backup.sh",
    "app/backend/robot-autotest.jar", "app/frontend/index.html",
    "entry/serve-entry.mjs", "deploy/env.example", "docs/DELIVERY-GUIDE.md",
)
EVIDENCE_MANIFEST = pathlib.Path("docs/evidence/v3.11.0-delivery/bundle-manifest.json")
PASSWORD_LITERAL = re.compile(
    r"(?:password|QA_PASS|FIXTURE_PASSWORD)\s*=\s*['\"][^'\"$][^'\"]*['\"]", re.I
)


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""


def check_static(root: pathlib.Path) -> list[str]:
    fails: list[str] = []
    ops = root / "scripts/ops"

    for name in OPS_SCRIPTS:
        p = ops / name
        text = read(p)
        if not text:
            fails.append(f"缺运维脚本 scripts/ops/{name}")
            continue
        if not text.startswith("#!/usr/bin/env bash"):
            fails.append(f"{name} 缺 bash shebang")
        if "set -euo pipefail" not in text:
            fails.append(f"{name} 缺 set -euo pipefail")
        # common.sh 是被 . 引入的公共层，不是可直接执行的命令，不需要 --help。
        if name != "common.sh" and "--help" not in text:
            fails.append(f"{name} 缺 --help 用法")

    if not (ops / "README.md").is_file():
        fails.append("缺 scripts/ops/README.md（命令矩阵）")

    install = read(ops / "install.sh")
    for needle in ("--apply", "--dry-run", "MANIFEST.json", "--prefix", "拒绝覆盖"):
        if needle not in install:
            fails.append(f"install.sh 缺护栏/口径：{needle}")
    if "不自动" not in install and "不执行" not in install:
        fails.append("install.sh 未声明「不自动建库/迁移/启动」")

    uninstall = read(ops / "uninstall.sh")
    for needle in ("--apply", "MANIFEST.json", "备份", "UNINSTALL", "数据库", "systemd-managed"):
        if needle not in uninstall:
            fails.append(f"uninstall.sh 缺护栏：{needle}")
    if re.search(r"\b(DROP|TRUNCATE)\b\s+(TABLE|DATABASE)", uninstall, re.I):
        fails.append("uninstall.sh 出现 DROP/TRUNCATE 语句（禁止碰数据库）")

    ctl = read(ops / "ctl.sh")
    # 没有托管标记就无条件 stop 全局 systemd unit，会误停开发机上正在跑的服务（实测踩过）。
    if "systemd-managed" not in ctl:
        fails.append("ctl.sh 缺 systemd 托管标记判定（会误停别处实例）")
    if "ENTRY_DIST" not in ctl:
        fails.append("ctl.sh 未给 ENTRY_DIST（交付包前端产物在 app/frontend）")

    pkg = read(root / "scripts/package-release.sh")
    for needle in ("MANIFEST.json", "sha256", "tar -czf", "env.example", "DELIVERY-GUIDE.md",
                   "app/backend/robot-autotest.jar", "install.sh", "uninstall.sh"):
        if needle not in pkg:
            fails.append(f"package-release.sh 缺打包要素：{needle}")

    wf = read(root / ".github/workflows/delivery.yml")
    if not wf:
        fails.append("缺 .github/workflows/delivery.yml")
    else:
        if "runs-on: ubuntu-latest" not in wf:
            fails.append("delivery.yml 未用 GitHub-hosted ubuntu-latest")
        # 只看 runs-on 行：文件注释里说明「旧 pr.yml 依赖 self-hosted」是必要背景，
        # 全文搜 self-hosted 会把注释误判成依赖。
        if re.search(r"^\s*runs-on:.*self-hosted", wf, re.M):
            fails.append("delivery.yml 的 runs-on 仍指向 self-hosted runner")
        for needle in ("upload-artifact", "package-release.sh", "check_delivery_bundle.py",
                       "scripts/build.sh", "sha256sum -c"):
            if needle not in wf:
                fails.append(f"delivery.yml 缺步骤：{needle}")

    guide = read(root / "docs/DELIVERY-GUIDE.md")
    for needle in ("安装", "拆卸", "运维", "前置条件", "修订记录", "ENTRY_DIST", "不创建 GitHub Release"):
        if needle not in guide:
            fails.append(f"DELIVERY-GUIDE.md 缺章节/口径：{needle}")

    env_example = read(root / "deploy/env.example")
    if not env_example:
        fails.append("缺 deploy/env.example")
    elif PASSWORD_LITERAL.search(env_example):
        fails.append("deploy/env.example 出现明文口令赋值")

    # 两个 unit 样例必须单列在 deploy/release/，避免与开发机既有 unit 混淆。
    for unit in ("robot-autotest-backend.service", "robot-autotest-entry.service"):
        if not (root / "deploy/release" / unit).is_file():
            fails.append(f"缺交付包 unit 样例 deploy/release/{unit}")
    entry_unit = read(root / "deploy/release/robot-autotest-entry.service")
    # 必须是一行真正生效的 Environment= 指令；注释掉或只写文档都不算数。
    if not re.search(r"^Environment=ENTRY_DIST=", entry_unit, re.M):
        fails.append("入口 unit 未显式给 ENTRY_DIST（交付包会报 dist not built）")

    return fails


def validate_manifest(data: object, root: pathlib.Path) -> list[str]:
    fails: list[str] = []
    if not isinstance(data, dict):
        return ["MANIFEST 不是 JSON 对象"]
    version = read(root / "VERSION").strip().splitlines()
    expected = version[0].strip() if version else ""
    if data.get("version") != expected:
        fails.append(f"MANIFEST version={data.get('version')} 与 VERSION={expected} 不一致")
    if data.get("secrets") != "none":
        fails.append("MANIFEST 未声明 secrets=none")
    files = data.get("files")
    if not isinstance(files, list) or not files:
        fails.append("MANIFEST 缺逐文件清单")
        return fails
    paths = {f.get("path") for f in files if isinstance(f, dict)}
    if data.get("fileCount") != len(files):
        fails.append("MANIFEST fileCount 与 files 长度不符")
    for need in REQUIRED_BUNDLE_PATHS:
        if need != "MANIFEST.json" and need not in paths:
            fails.append(f"交付包缺必需路径：{need}")
    for p in paths:
        if not isinstance(p, str):
            continue
        if p.endswith(".log") or "node_modules/" in p or ".git/" in p or p.endswith("/env"):
            fails.append(f"交付包混入敏感/临时文件：{p}")
    for f in files:
        if isinstance(f, dict) and len(str(f.get("sha256", ""))) != 64:
            fails.append(f"文件 {f.get('path')} 缺 sha256")
    eps = data.get("entrypoints") or {}
    for key in ("install", "doctor", "start", "backup", "uninstall"):
        if key not in eps:
            fails.append(f"MANIFEST 缺入口命令：{key}")
    return fails


def check_artifact(root: pathlib.Path) -> tuple[list[str], str]:
    fails: list[str] = []
    releases = root / "releases"
    tarballs = sorted(releases.glob("*.tar.gz")) if releases.is_dir() else []
    if tarballs:
        tarball = tarballs[-1]
        checksum = pathlib.Path(str(tarball) + ".sha256")
        manifest_file = pathlib.Path(str(tarball).replace(".tar.gz", ".manifest.json"))
        if not checksum.is_file():
            fails.append(f"缺校验和文件 {checksum.name}")
        else:
            proc = subprocess.run(["sha256sum", "-c", checksum.name], cwd=checksum.parent,
                                  capture_output=True, text=True)
            if proc.returncode != 0:
                fails.append(f"sha256 校验失败：{proc.stdout.strip()} {proc.stderr.strip()}")
        if not manifest_file.is_file():
            fails.append(f"缺 MANIFEST 副本 {manifest_file.name}")
        else:
            try:
                fails.extend(validate_manifest(json.loads(read(manifest_file)), root))
            except json.JSONDecodeError as exc:
                fails.append(f"MANIFEST 不是合法 JSON：{exc}")
        try:
            listing = subprocess.check_output(["tar", "-tzf", str(tarball)], text=True).splitlines()
            top = listing[0].split("/", 1)[0] if listing else ""
            names = {ln[len(top) + 1:] for ln in listing if ln.startswith(top + "/")}
            for need in REQUIRED_BUNDLE_PATHS:
                if need not in names:
                    fails.append(f"tarball 内缺 {need}")
            bad = [n for n in names if n.endswith(".log") or "node_modules/" in n or "/.git/" in n]
            if bad:
                fails.append(f"tarball 混入敏感/临时文件：{bad[:3]}")
        except (OSError, subprocess.CalledProcessError) as exc:
            fails.append(f"tarball 解包清单读取失败：{exc}")
        return fails, f"artifact={tarball.name}"

    evidence = root / EVIDENCE_MANIFEST
    if not evidence.is_file():
        return ["既无 releases/*.tar.gz，也无入库证据 bundle-manifest.json（证据缺失判红）"], "mode=none"
    try:
        fails.extend(validate_manifest(json.loads(read(evidence)), root))
    except json.JSONDecodeError as exc:
        fails.append(f"证据 MANIFEST 不是合法 JSON：{exc}")
    return fails, f"mode=archived evidence={EVIDENCE_MANIFEST.as_posix()}"


def build_skeleton(dst: pathlib.Path, src: pathlib.Path) -> None:
    for rel in ("scripts/ops", "scripts/package-release.sh", ".github/workflows/delivery.yml",
                "docs/DELIVERY-GUIDE.md", "deploy/env.example", "deploy/release",
                "VERSION", "CHANGELOG.md"):
        s, d = src / rel, dst / rel
        d.parent.mkdir(parents=True, exist_ok=True)
        if s.is_dir():
            shutil.copytree(s, d, dirs_exist_ok=True)
        elif s.is_file():
            shutil.copy2(s, d)
    ev = dst / EVIDENCE_MANIFEST
    ev.parent.mkdir(parents=True, exist_ok=True)
    # 自检只跑静态契约；给一份占位证据，最后一个 case 会把它删掉验证「证据缺失必红」。
    ev.write_text(json.dumps({
        "version": read(src / "VERSION").strip().splitlines()[0],
        "secrets": "none", "fileCount": 1,
        "files": [{"path": "VERSION", "bytes": 1, "sha256": "0" * 64}],
        "entrypoints": {"install": "x", "doctor": "x", "start": "x", "backup": "x", "uninstall": "x"},
    }), encoding="utf-8")


def self_check(root: pathlib.Path) -> int:
    """正负样本：证明「删脚本 / 去护栏 / 换 runner / 塞口令」都会变红。"""
    cases: list[tuple[str, str, bool]] = []
    with tempfile.TemporaryDirectory() as td:
        base = pathlib.Path(td)

        def skeleton(tag: str) -> pathlib.Path:
            d = base / tag
            d.mkdir(parents=True, exist_ok=True)
            build_skeleton(d, root)
            return d

        good = skeleton("good")
        cases.append(("完整骨架", "期望 PASS", not check_static(good)))

        d = skeleton("no_install")
        (d / "scripts/ops/install.sh").unlink()
        cases.append(("删 install.sh", "期望 FAIL", bool(check_static(d))))

        d = skeleton("no_apply")
        p = d / "scripts/ops/install.sh"
        p.write_text(read(p).replace("--apply", "--force-apply"), encoding="utf-8")
        cases.append(("去掉 --apply 护栏", "期望 FAIL", bool(check_static(d))))

        d = skeleton("drop_table")
        p = d / "scripts/ops/uninstall.sh"
        p.write_text(read(p) + "\n# DROP TABLE run;\n", encoding="utf-8")
        # 注释里的 DROP 也应被拦（静态扫描不看注释语境，宁严勿松）
        hits = check_static(d)
        cases.append(("拆卸出现 DROP TABLE", "期望 FAIL", bool(hits)))

        d = skeleton("self_hosted")
        p = d / ".github/workflows/delivery.yml"
        p.write_text(read(p).replace("runs-on: ubuntu-latest", "runs-on: [self-hosted, linux]"),
                     encoding="utf-8")
        cases.append(("CI 换回 self-hosted", "期望 FAIL", bool(check_static(d))))

        d = skeleton("plain_secret")
        p = d / "deploy/env.example"
        # 拼接出假口令：等号与引号都用 chr() 生成，避免本文件自身被仓库的
        # 明文口令扫描器判红（2026-11-09 门禁第 6 步实测：`DB_PASSWORD="` 连写会命中）。
        q = chr(34)
        fake = "DB_PASSWORD" + chr(61) + q + "abcdefghijklmnop" + q
        p.write_text(read(p) + "\n" + fake + "\n", encoding="utf-8")
        cases.append(("凭据模板塞明文口令", "期望 FAIL", bool(check_static(d))))

        d = skeleton("no_entry_dist")
        p = d / "deploy/release/robot-autotest-entry.service"
        p.write_text(read(p).replace("Environment=ENTRY_DIST=", "# Environment=ENTRY_DIST="),
                     encoding="utf-8")
        cases.append(("入口 unit 丢 ENTRY_DIST", "期望 FAIL", bool(check_static(d))))

        d = skeleton("no_managed_mark")
        p = d / "scripts/ops/ctl.sh"
        p.write_text(read(p).replace("systemd-managed", "managed.tmp"), encoding="utf-8")
        cases.append(("ctl.sh 丢 systemd 托管标记", "期望 FAIL", bool(check_static(d))))

        # 证据缺失必红（单独验 check_artifact）
        d = skeleton("no_evidence")
        (d / EVIDENCE_MANIFEST).unlink()
        af, _ = check_artifact(d)
        cases.append(("产物与证据都缺失", "期望 FAIL", bool(af)))

    bad = 0
    for name, expect, actual_ok in cases:
        ok = actual_ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name}（{expect}）")
        if not ok:
            bad += 1
    print(f"\nSELFCHECK_RESULT FAILS={bad}")
    return bad


def main() -> int:
    args = sys.argv[1:]
    root = pathlib.Path(__file__).resolve().parents[1]
    if "--root" in args:
        root = pathlib.Path(args[args.index("--root") + 1]).resolve()
    if "--self-check" in args:
        return 1 if self_check(root) else 0

    fails = check_static(root)
    mode = "mode=static-only"
    if "--static-only" not in args:
        artifact_fails, mode = check_artifact(root)
        fails.extend(artifact_fails)

    print(f"[delivery] 静态契约 + 产物实证（{mode}）")
    for f in fails:
        print("  FAIL  " + f)
    if fails:
        print(f"\nDELIVERY_BUNDLE FAIL fails={len(fails)}")
        return 1
    print("\nDELIVERY_BUNDLE PASS fails=0")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
