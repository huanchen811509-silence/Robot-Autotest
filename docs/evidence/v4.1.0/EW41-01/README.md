# EW41-01 · 工作区目录契约与机检（FR-v4.1-01 / FR-v4.1-02）

| 项 | 内容 |
|---|---|
| 工作包 | `W41-01` 契约冻结 + ADR + 机检 |
| 需求 | `FR-v4.1-01`（目录结构 / `formatVersion=1` / manifest 双向一致 / HMAC-SHA256）、`FR-v4.1-02`（手工改动需显式导入） |
| 日期 | 2026-11-09 |
| 环境 | Ubuntu-24.04 · Python 3 · 仓库根 `robot-autotest` |
| 结论 | `PASS`：契约机检 `WORKSPACE_BUNDLE PASS`，负向自证 15/15 判红命中、`fails=0` |

---

## 1. 判据与读数

| 判据 | 命令 | 真实读数 |
|---|---|---|
| 契约文档 + ADR + 迁移成对 + TenantGuard + 服务类 + 生命周期 | `python3 tools/check_workspace_bundle.py` | `WORKSPACE_BUNDLE PASS`（见 `workspace-check.log`） |
| 判据本身有效（负向自证） | `python3 tools/check_workspace_bundle.py --self-check` | 15 项全 `PASS`、`WORKSPACE_BUNDLE_SELFCHECK PASS fails=0`（见 `workspace-selfcheck.log`） |
| ADR 四份且已登记 | `ls docs/adr/ADR-4X-1[2-5]-*.md` + `docs/adr/README.md` | `ADR-4X-12` 签名 / `-13` 稳定业务键 / `-14` 升级前备份 / `-15` 生命周期，四份齐全并登记 |
| 迁移上下成对 | `ls db/migration/V11_28__*.sql db/down/V11_28__*.down.sql` | `V11_28__v4_1_workspace_job.sql` ↔ `V11_28__v4_1_workspace_job.down.sql`（ADR-0003 手写 down） |
| 多租户元测试前提 | `grep workspace_job TenantGuard.java` | `"workspace_job", "project_member"`（第 61 行） |
| 门禁接线 | `bash scripts/gates.sh` | 第 32 步 `workspace_bundle`；删步必红（`--selfcheck` 实证） |

## 2. 实现口径

| 项 | 落地 |
|---|---|
| 契约真源 | `docs/contracts/workspace-bundle.md`（目录结构 / `formatVersion` / 签名算法 / 显式导入四类关键词） |
| 目录 | `project.json`、`manifest.json`、`members.json`、`README.txt` + `cases/ case-sets/ assets/ environments/ reports/ runs/ files/` |
| 一致性 | manifest 与目录双向比对 + 每文件 `sha256` 与 `bytes` 复核；清单不含 `manifest.json` 自身 |
| 签名 | `HMAC-SHA256`，`signature.value` 为 64 位小写十六进制；`--key` 给出时做常量时间验签 |
| 安全 | 路径拒绝绝对路径 / 盘符 / `..` / 控制字符；JSON 键名命中口令类且未脱敏即判红；出现 `PRIVATE KEY` 判红 |
| 负向样本 | 缺目录 / 篡改文件 / 多文件 / 缺签名 / 错密钥 / 明文凭据 / `formatVersion=2` / 仓库缺 ADR / 缺 down / 缺服务类 / KEEP_DIRS 丢 workspace / backup 丢 workspace / 只读报错被抹平 |

## 3. 证据文件

| 文件 | 内容 |
|---|---|
| `workspace-check.log` | 正式机检输出（`PASS`） |
| `workspace-selfcheck.log` | 15 项负向自证逐项读数 |
| `contract-inventory.log` | ADR / 迁移成对 / TenantGuard / 服务类清单 |

## 4. 判不了的部分（不冒充通过）

| # | 项 | 原因 | 补齐 |
|---|---|---|---|
| 1 | 跨实例真实导入的运行时一致性 | 契约机检只证明静态结构；运行时由 `EW41-02/03/04` 的 `workspace-contract.sh` 以真库验证 | 已由 W41-02/03 覆盖 |
| 2 | 只读文件系统挂载实测 | 本机无只读挂载环境，静态判据只证明错误码分支存在 | 真机抽查一档（`chmod -w` 目录）即可 |
