#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""工作区目录契约机检（FR-v4.1-01 / FR-v4.1-02，契约见 docs/contracts/workspace-bundle.md）。

判据：
  ① 契约文档存在，且包含目录结构 / formatVersion / HMAC-SHA256 / 手工改动显式导入 四类关键词；
  ② ADR-4X-12..15 四份齐全，且都在 docs/adr/README.md 索引里；
  ③ db/migration/V11_28 与 db/down/V11_28 成对存在（ADR-0003 手写 down）；
  ④ TenantGuard 已登记 workspace_job（多租户元测试的前提）；
  ⑤ 后端工作区服务类存在（导出 / 导入 / 签名 / 脚本扫描）；
  ⑥ .gitignore 忽略 /workspace/（ADR-4X-15）；
  ⑦ --bundle 指定包时：目录齐全、manifest 双向一致、sha256/字节数一致、签名格式合法、
     凭据无明文、formatVersion=1、目录名与 project code 一致。

用法：
  python tools/check_workspace_bundle.py
  python tools/check_workspace_bundle.py --bundle fixtures/workspace/demo-bundle
  python tools/check_workspace_bundle.py --bundle <dir> --key <hex>   # 额外做 HMAC 校验
  python tools/check_workspace_bundle.py --self-check                  # 负向自证（临时目录，不碰仓库）
退出码：0 全 PASS；1 判红；2 用法错误。
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import shutil
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

REQUIRED_DIRS = ["cases", "case-sets", "assets", "environments", "reports", "runs", "files"]
REQUIRED_TOP = ["project.json", "manifest.json", "members.json", "README.txt"]
FORBIDDEN_KEY = ("password", "passwd", "secret", "token", "privatekey", "api_key", "apikey")
SERVICE_FILES = [
    "WorkspaceExportService.java",
    "WorkspaceImportService.java",
    "WorkspaceBundleSigner.java",
    "ScriptSafetyScanner.java",
]
REQUIRED_SECTIONS = [
    "## 1. 目录结构",
    "formatVersion",
    "HMAC-SHA256",
    "手工改动",
]


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical(obj) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def sign_manifest(manifest: dict, key_hex: str) -> str:
    body = {k: v for k, v in manifest.items() if k != "signature"}
    return hmac.new(bytes.fromhex(key_hex), canonical(body).encode("utf-8"), hashlib.sha256).hexdigest()


def is_safe_rel(path: str) -> bool:
    if not path or path.startswith("/") or "\\" in path:
        return False
    if ":" in path:  # 盘符
        return False
    parts = path.split("/")
    if any(p in ("", ".", "..") for p in parts):
        return False
    return all(ord(c) >= 32 for c in path)


def scan_json_secrets(node, trail="", out=None):
    out = [] if out is None else out
    if isinstance(node, dict):
        for k, v in node.items():
            low = str(k).lower().replace("-", "_")
            if any(f in low for f in FORBIDDEN_KEY):
                if isinstance(v, str) and (v == "<redacted>" or len(v) <= 12 and v.isalnum()):
                    pass  # 已脱敏 / 掩码指纹
                else:
                    out.append("%s.%s" % (trail or "$", k))
            scan_json_secrets(v, "%s.%s" % (trail, k), out)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            scan_json_secrets(v, "%s[%d]" % (trail, i), out)
    return out


def check_bundle(bundle: Path, key_hex: str | None = None) -> list[str]:
    errs: list[str] = []
    bundle = Path(bundle)
    if not bundle.is_dir():
        return ["BUNDLE 不存在: %s" % bundle]
    for d in REQUIRED_DIRS:
        if not (bundle / d).is_dir():
            errs.append("缺少目录 %s/" % d)
    for f in REQUIRED_TOP:
        if not (bundle / f).is_file():
            errs.append("缺少文件 %s" % f)
    if errs:
        return errs

    try:
        project = json.loads((bundle / "project.json").read_text(encoding="utf-8"))
        manifest = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return ["JSON 解析失败: %s" % exc]

    if project.get("formatVersion") != 1:
        errs.append("project.json formatVersion 必须为 1（实际 %r）" % project.get("formatVersion"))
    if manifest.get("formatVersion") != 1:
        errs.append("manifest.json formatVersion 必须为 1（实际 %r）" % manifest.get("formatVersion"))
    if str(project.get("code", "")).lower() != bundle.name.lower():
        errs.append("project.json code=%r 与目录名 %r 不一致" % (project.get("code"), bundle.name))
    if project.get("granularity") not in ("project", "dir", "file"):
        errs.append("granularity 非法: %r" % project.get("granularity"))

    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        errs.append("manifest.files 必须为非空数组")
        return errs
    listed = {}
    for item in files:
        p = str(item.get("path", ""))
        if not is_safe_rel(p):
            errs.append("manifest 路径不安全: %r" % p)
            continue
        if p == "manifest.json":
            errs.append("manifest.files 不应包含 manifest.json 自身")
            continue
        if p in listed:
            errs.append("manifest 重复条目: %s" % p)
        listed[p] = item

    actual = {}
    for p in bundle.rglob("*"):
        if p.is_file():
            rel = p.relative_to(bundle).as_posix()
            if rel != "manifest.json":
                actual[rel] = p
    for p in sorted(set(listed) - set(actual)):
        errs.append("清单有、目录无: %s" % p)
    for p in sorted(set(actual) - set(listed)):
        errs.append("目录有、清单无: %s" % p)

    for p, item in sorted(listed.items()):
        f = actual.get(p)
        if not f:
            continue
        data = f.read_bytes()
        if sha256_bytes(data) != str(item.get("sha256", "")).lower():
            errs.append("sha256 不一致: %s" % p)
        if len(data) != int(item.get("bytes", -1)):
            errs.append("字节数不一致: %s (%d != %r)" % (p, len(data), item.get("bytes")))

    sig = manifest.get("signature") or {}
    if sig.get("algorithm") != "HMAC-SHA256":
        errs.append("signature.algorithm 必须为 HMAC-SHA256")
    if not sig.get("keyId"):
        errs.append("signature.keyId 缺失")
    value = str(sig.get("value", ""))
    if len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
        errs.append("signature.value 必须是 64 位小写十六进制")
    if key_hex:
        expected = sign_manifest(manifest, key_hex)
        if not hmac.compare_digest(expected, value):
            errs.append("HMAC 校验失败（包被篡改或密钥不符）")

    for f in list((bundle).rglob("*.json")):
        try:
            scan = scan_json_secrets(json.loads(f.read_text(encoding="utf-8")))
        except json.JSONDecodeError as exc:
            errs.append("JSON 解析失败 %s: %s" % (f.relative_to(bundle).as_posix(), exc))
            continue
        for hit in scan:
            errs.append("疑似明文凭据 %s @ %s" % (hit, f.relative_to(bundle).as_posix()))
    for f in bundle.rglob("*"):
        if f.is_file() and "PRIVATE KEY" in f.read_text(encoding="utf-8", errors="ignore"):
            errs.append("出现私钥材料: %s" % f.relative_to(bundle).as_posix())
    readme = (bundle / "README.txt").read_text(encoding="utf-8", errors="ignore")
    if "显式导入" not in readme:
        errs.append("README.txt 缺少「手工改动需显式导入才生效」说明")
    return errs


def check_repo(root: Path) -> list[str]:
    errs: list[str] = []
    contract = root / "docs/contracts/workspace-bundle.md"
    if not contract.is_file():
        errs.append("缺少 docs/contracts/workspace-bundle.md")
    else:
        text = contract.read_text(encoding="utf-8")
        for sec in REQUIRED_SECTIONS:
            if sec not in text:
                errs.append("契约文档缺少关键词: %s" % sec)
    adr_readme = root / "docs/adr/README.md"
    adr_text = adr_readme.read_text(encoding="utf-8") if adr_readme.is_file() else ""
    for no in ("12", "13", "14", "15"):
        matches = sorted((root / "docs/adr").glob("ADR-4X-%s-*.md" % no))
        if not matches:
            errs.append("缺少 ADR-4X-%s" % no)
        elif matches[0].name not in adr_text:
            errs.append("ADR-4X-%s 未登记到 docs/adr/README.md" % no)
    up = sorted((root / "db/migration").glob("V11_28__*.sql"))
    down = sorted((root / "db/down").glob("V11_28__*.down.sql"))
    if len(up) != 1:
        errs.append("db/migration 需恰好一个 V11_28__*.sql（实际 %d）" % len(up))
    if len(down) != 1:
        errs.append("db/down 需恰好一个 V11_28__*.down.sql（实际 %d）" % len(down))
    guard = root / "backend/src/main/java/com/qa/autotest/common/tenant/TenantGuard.java"
    if not guard.is_file():
        errs.append("找不到 TenantGuard.java")
    elif "workspace_job" not in guard.read_text(encoding="utf-8"):
        errs.append("TenantGuard 未登记 workspace_job")
    ws_dir = root / "backend/src/main/java/com/qa/autotest/application/workspace"
    for name in SERVICE_FILES:
        if not (ws_dir / name).is_file():
            errs.append("缺少工作区服务类 application/workspace/%s" % name)
    gi = root / ".gitignore"
    if not gi.is_file() or "/workspace/" not in gi.read_text(encoding="utf-8"):
        errs.append(".gitignore 未忽略 /workspace/")
    # 生命周期属主（FR-v4.1-07）：拆卸保留区、备份覆盖、只读文件系统明确报错。
    uninstall = root / "scripts/ops/uninstall.sh"
    if not uninstall.is_file():
        errs.append("缺少 scripts/ops/uninstall.sh")
    else:
        keep = next((ln for ln in uninstall.read_text(encoding="utf-8").splitlines()
                     if ln.strip().startswith("KEEP_DIRS=")), "")
        if "workspace" not in keep:
            errs.append("uninstall.sh 的 KEEP_DIRS 未包含 workspace")
    backup = root / "scripts/ops/backup.sh"
    if not backup.is_file():
        errs.append("缺少 scripts/ops/backup.sh")
    else:
        bt = backup.read_text(encoding="utf-8")
        if "workspace" not in bt or "tar" not in bt:
            errs.append("backup.sh 未覆盖 workspace 目录")
    writer = ws_dir / "WorkspaceBundleWriter.java"
    if writer.is_file() and "只读文件系统" not in writer.read_text(encoding="utf-8"):
        errs.append("导出未对只读文件系统明确报错")
    return errs


# ---------------------------------------------------------------- self-check
def make_good_bundle(dir: Path, key_hex: str) -> dict:
    code = dir.name
    project = {
        "formatVersion": 1, "code": code, "name": "演示", "description": "",
        "testKernel": "ROBOT", "exportedAt": "2026-11-09T00:00:00Z",
        "generator": "robot-autotest/4.1.0", "granularity": "project", "rootPath": None,
    }
    (dir / "project.json").write_text(json.dumps(project, ensure_ascii=False, indent=2), encoding="utf-8")
    (dir / "members.json").write_text(json.dumps({"members": [{"login": "qa", "displayName": "QA", "role": "member"}]}, ensure_ascii=False), encoding="utf-8")
    (dir / "README.txt").write_text("本目录是交换格式：手工改动需显式导入才生效。\n", encoding="utf-8")
    payload = {
        "cases/cases.json": {"cases": [{"caseCode": "C-001", "name": "登录成功", "scriptPath": "files/login.robot", "steps": []}]},
        "case-sets/case-sets.json": {"sets": [{"name": "冒烟集", "items": [{"caseCode": "C-001", "seqNo": 1}]}]},
        "assets/assets.json": {"assets": [{"name": "设备A", "type": "DEVICE"}]},
        "assets/credentials.json": {"credentials": [{"name": "qa-admin", "mask": "a1b2c3d4e5f6"}]},
        "environments/environments.json": {"environments": [{"name": "预发", "profileType": "SSH", "values": {"HOST": "10.0.0.2"}}]},
        "reports/reports.json": {"reports": []},
        "runs/runs.json": {"runs": []},
    }
    for d in REQUIRED_DIRS:
        (dir / d).mkdir(parents=True, exist_ok=True)
    for p, obj in payload.items():
        (dir / p).parent.mkdir(parents=True, exist_ok=True)
        (dir / p).write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")
    (dir / "files/login.robot").write_text("*** Settings ***\nLibrary    Browser\n", encoding="utf-8")
    files = []
    for p in sorted(dir.rglob("*")):
        if p.is_file():
            data = p.read_bytes()
            files.append({"path": p.relative_to(dir).as_posix(), "sha256": sha256_bytes(data), "bytes": len(data)})
    manifest = {
        "formatVersion": 1, "projectCode": code, "exportedAt": "2026-11-09T00:00:00Z",
        "generator": "robot-autotest/4.1.0", "granularity": "project", "rootPath": None,
        "files": files,
    }
    key = bytes.fromhex(key_hex)
    manifest["signature"] = {
        "algorithm": "HMAC-SHA256", "keyId": "server-local-v1",
        "value": hmac.new(key, canonical(manifest).encode("utf-8"), hashlib.sha256).hexdigest(),
    }
    (dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


def refresh_manifest(dir: Path, key_hex: str) -> None:
    m = json.loads((dir / "manifest.json").read_text(encoding="utf-8"))
    files = []
    for p in sorted(dir.rglob("*")):
        if p.is_file() and p.name != "manifest.json":
            data = p.read_bytes()
            files.append({"path": p.relative_to(dir).as_posix(), "sha256": sha256_bytes(data), "bytes": len(data)})
    m["files"] = files
    body = {k: v for k, v in m.items() if k != "signature"}
    m["signature"] = {
        "algorithm": "HMAC-SHA256", "keyId": "server-local-v1",
        "value": hmac.new(bytes.fromhex(key_hex), canonical(body).encode("utf-8"),
                          hashlib.sha256).hexdigest(),
    }
    (dir / "manifest.json").write_text(json.dumps(m, ensure_ascii=False, indent=2), encoding="utf-8")


def self_check() -> int:
    key_hex = "11" * 32
    fails = 0
    tmp = Path(tempfile.mkdtemp(prefix="wsbundle-"))
    try:
        good = tmp / "DEMO"
        good.mkdir()
        make_good_bundle(good, key_hex)
        def expect(name, fn, clean=False):
            nonlocal fails
            errs = fn()
            ok = bool(errs) == (not clean)
            print("%s %s%s" % ("PASS" if ok else "FAIL", name, "" if ok else " -> " + "; ".join(errs)))
            if not ok:
                fails += 1

        expect("good", lambda: check_bundle(good, key_hex), clean=True)

        def missing_dir():
            (good / "reports").rename(tmp / "x")
            e = check_bundle(good, key_hex)
            (tmp / "x").rename(good / "reports")
            return e
        expect("missing-dir", missing_dir)

        def tamper():
            p = good / "files/login.robot"
            old = p.read_text(encoding="utf-8")
            p.write_text(old + "# tampered\n", encoding="utf-8")
            e = check_bundle(good, key_hex)
            p.write_text(old, encoding="utf-8")
            return e
        expect("tampered-file", tamper)

        def extra():
            (good / "files/extra.txt").write_text("x", encoding="utf-8")
            e = check_bundle(good, key_hex)
            (good / "files/extra.txt").unlink()
            return e
        expect("extra-file", extra)

        def no_sig():
            m = json.loads((good / "manifest.json").read_text(encoding="utf-8"))
            m.pop("signature")
            (good / "manifest.json").write_text(json.dumps(m), encoding="utf-8")
            e = check_bundle(good, key_hex)
            refresh_manifest(good, key_hex)
            return e
        expect("missing-signature", no_sig)

        def bad_sig_key():
            return check_bundle(good, "22" * 32)
        expect("wrong-key", bad_sig_key)

        def plaintext_secret():
            p = good / "assets/credentials.json"
            old = p.read_text(encoding="utf-8")
            p.write_text(json.dumps({"credentials": [{"name": "x", "password": "P@ssw0rd"}]}), encoding="utf-8")
            e = check_bundle(good)
            p.write_text(old, encoding="utf-8")
            return e
        expect("plaintext-credential", plaintext_secret)

        def bad_version():
            p = good / "project.json"
            old = p.read_text(encoding="utf-8")
            obj = json.loads(old)
            obj["formatVersion"] = 2
            p.write_text(json.dumps(obj), encoding="utf-8")
            e = check_bundle(good)
            p.write_text(old, encoding="utf-8")
            return e
        expect("bad-format-version", bad_version)

        # 仓库契约负向：临时仓删掉 ADR-4X-13、TenantGuard 登记、迁移 down，逐项必红
        repo = tmp / "repo"
        (repo / "docs/adr").mkdir(parents=True)
        shutil.copytree(REPO / "docs/adr", repo / "docs/adr", dirs_exist_ok=True)
        (repo / "docs/contracts").mkdir(parents=True)
        shutil.copy(REPO / "docs/contracts/workspace-bundle.md", repo / "docs/contracts/workspace-bundle.md")
        (repo / "db/migration").mkdir(parents=True)
        (repo / "db/down").mkdir(parents=True)
        for p in sorted((REPO / "db/migration").glob("V11_28__*.sql")):
            shutil.copy(p, repo / "db/migration" / p.name)
        for p in sorted((REPO / "db/down").glob("V11_28__*.down.sql")):
            shutil.copy(p, repo / "db/down" / p.name)
        ws_src = REPO / "backend/src/main/java/com/qa/autotest/application/workspace"
        ws_dst = repo / "backend/src/main/java/com/qa/autotest/application/workspace"
        ws_dst.mkdir(parents=True)
        if ws_src.is_dir():
            for f in ws_src.glob("*.java"):
                shutil.copy(f, ws_dst / f.name)
        (repo / "backend/src/main/java/com/qa/autotest/common/tenant").mkdir(parents=True)
        shutil.copy(REPO / "backend/src/main/java/com/qa/autotest/common/tenant/TenantGuard.java",
                    repo / "backend/src/main/java/com/qa/autotest/common/tenant/TenantGuard.java")
        shutil.copy(REPO / ".gitignore", repo / ".gitignore")
        (repo / "scripts/ops").mkdir(parents=True)
        shutil.copy(REPO / "scripts/ops/uninstall.sh", repo / "scripts/ops/uninstall.sh")
        shutil.copy(REPO / "scripts/ops/backup.sh", repo / "scripts/ops/backup.sh")
        expect("repo-good", lambda: check_repo(repo), clean=True)

        def repo_drop_adr():
            f = next((repo / "docs/adr").glob("ADR-4X-13-*.md"))
            f.unlink()
            e = check_repo(repo)
            shutil.copy(REPO / "docs/adr" / f.name, repo / "docs/adr" / f.name)
            return e
        expect("repo-missing-adr", repo_drop_adr)

        def repo_no_down():
            f = next((repo / "db/down").glob("V11_28__*.down.sql"))
            f.unlink()
            e = check_repo(repo)
            shutil.copy(REPO / "db/down" / f.name, repo / "db/down" / f.name)
            return e
        expect("repo-missing-down", repo_no_down)

        def repo_no_service():
            f = repo / "backend/src/main/java/com/qa/autotest/application/workspace/WorkspaceBundleSigner.java"
            f.unlink()
            e = check_repo(repo)
            shutil.copy(REPO / "backend/src/main/java/com/qa/autotest/application/workspace/WorkspaceBundleSigner.java", f)
            return e
        expect("repo-missing-service", repo_no_service)

        def repo_keep_no_ws():
            p = repo / "scripts/ops/uninstall.sh"
            old = p.read_text(encoding="utf-8")
            p.write_text(old.replace("backups workspace", "backups"), encoding="utf-8")
            e = check_repo(repo)
            p.write_text(old, encoding="utf-8")
            return e
        expect("repo-uninstall-drops-workspace", repo_keep_no_ws)

        def repo_backup_no_ws():
            p = repo / "scripts/ops/backup.sh"
            old = p.read_text(encoding="utf-8")
            p.write_text(old.replace("workspace", "logs"), encoding="utf-8")
            e = check_repo(repo)
            p.write_text(old, encoding="utf-8")
            return e
        expect("repo-backup-drops-workspace", repo_backup_no_ws)

        def repo_no_readonly_msg():
            p = repo / "backend/src/main/java/com/qa/autotest/application/workspace/WorkspaceBundleWriter.java"
            old = p.read_text(encoding="utf-8")
            p.write_text(old.replace("只读文件系统", "磁盘错误"), encoding="utf-8")
            e = check_repo(repo)
            p.write_text(old, encoding="utf-8")
            return e
        expect("repo-readonly-not-explicit", repo_no_readonly_msg)

        print("WORKSPACE_BUNDLE_SELFCHECK %s fails=%d" % ("PASS" if fails == 0 else "FAIL", fails))
        return 0 if fails == 0 else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle", help="待校验的工作区目录")
    ap.add_argument("--key", help="HMAC 密钥（十六进制，可选；给出则做验签）")
    ap.add_argument("--self-check", action="store_true", help="负向自证")
    ap.add_argument("--root", default=str(REPO), help="仓库根（默认本脚本上一级）")
    args = ap.parse_args()
    if args.self_check:
        return self_check()
    if args.key:
        try:
            bytes.fromhex(args.key)
        except ValueError:
            print("FAIL 用法: --key 必须是十六进制")
            return 2

    errs = check_repo(Path(args.root))
    if args.bundle:
        errs += check_bundle(Path(args.bundle), args.key)
    if errs:
        for e in errs:
            print("FAIL %s" % e)
        print("WORKSPACE_BUNDLE FAIL")
        return 1
    print("WORKSPACE_BUNDLE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
