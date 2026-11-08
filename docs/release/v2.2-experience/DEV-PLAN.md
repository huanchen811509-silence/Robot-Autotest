# v2.2 体验与可观测 · ④ 开发计划

**文档编号** RA-REL-2026-022-D ｜ **版本** v2.2-experience ｜ **批次** B10 ｜ **门禁** G9
**日期** 2026-11-08 ｜ **状态** 已实施（26 包全落地并自测）；**G9 未判**
**形态** 指针式：逐包定义与偏差不改写，真源见 §2

---

## 1. 交付结构

| 层 | 包数 | 交付物落点 |
|---|---|---|
| 层 M | 20 | 后端领域 / 应用 / 基础设施 + 前端视图 + `tools/seed_ed202_organization.py` |
| 层 N | 6 | `application/ai/*`（`AiCliAdapter` / `AiFeatureGate` / `AiProcessManager` / `AiPanelService` / `AiCliProbe`）+ `api/ai/AiPanelController` |

## 2. 真源指针

| 内容 | 真源 |
|---|---|
| 逐包定义（WBS 编号 + 验收命令） | `docs/plan/v2.2-DEV-PLAN-增补草案-待评审-20261107.md` §9 |
| 证据目录 | `docs/evidence/v3.0.1/v22-*/`、`docs/evidence/v3.0.1/v22-ai/AI-01..06/` |
| 真实 CLI 链路证据 | `docs/evidence/v3.0.1/v23/G-AUTH/` |

## 3. 实测读数（2026-11-08 复核）

| 项 | 读数 |
|---|---|
| 后端单测 | `Tests run: 1062`（含本批新增 stdin 回归） |
| 前端 | `53 files / 370 tests` |
| 编辑器 | `24 files / 219 tests` |
| 公共用例库 | 项目 `77` `ED202-COMMON` / 813 用例 / 3078 步骤 / 7 用例集 / 39 场景 |
| 门禁 | 19 步 18 PASS / `FAILS=1`（唯一失败是第 13 步浏览器回归，与 v2.2 无关，见下） |

## 4. 本批修掉的一个真实缺陷

`AiProcessManager` 起 CLI 子进程后**没有关闭子进程的 stdin**。假 CLI 不读 stdin 所以一直没暴露；
2026-11-08 用真实 codex 跑后端链路时表现为「直连 14s 成功、走后端 60s 超时降级」。
修复：起进程后立即 `process.getOutputStream().close()`；回归 `AiProcessManagerStdinTest` + 夹具 `fixtures/ai/stdin-eof-probe.sh`。
修前 11.08s 判红、修后 0.337s 转绿，对照见 `docs/evidence/v3.0.1/v23/G-AUTH/raw/stdin-fix-redgreen.log`。

## 5. 与门禁第 13 步的关系

第 13 步失败是**环境问题**（Chrome CDP `:9222` 无监听），不是 v2.2 代码问题。
该步判定的是 v2.0/v3.0.1 的浏览器回归，v2.2 本身不以它作为 G9 的判据。

## 6. 明确不做

不改 WBS 分母；不 bump 版本；不建 tag；不判 G4 / G5 / G7 / G8 / G9。
