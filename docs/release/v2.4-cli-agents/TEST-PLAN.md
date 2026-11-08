# v2.4 CLI 多 agent · ⑤ TEST-PLAN

**文档编号** RA-REL-2026-024-T ｜ **版本** v2.4-cli-agents ｜ **批次** B12（待落号） ｜ **门禁** G11
**状态** 20 条 TC 已执行，16/16 验收项达成；真源见 `docs/plan/v2.4-TEST-PLAN-增补草案-待评审-20261108.md`

## 1. 用例与结果

| 段 | 编号 | 结果 |
|---|---|---|
| 候选与 argv | TC-CLI-01..06 | 6/6 PASS |
| 内置兜底 | TC-CLI-07..11 | 5/5 PASS |
| node 自解析 | TC-CLI-12..13 | 2/2 PASS（13 即缺陷复现） |
| 参数注入 | TC-CLI-14..16 | 3/3 PASS |
| 真实链路 | TC-CLI-17..20 | 4/4 PASS |

负向：N-CLI-01..05 全部按预期（其中两条为自然发生的反向证据，已留在日志里）。

## 2. 证据

| 编号 | 路径 |
|---|---|
| CLI-01 | `docs/evidence/v3.0.1/v24/CLI-01/raw/cli-probe.log` |
| CLI-02 | `docs/evidence/v3.0.1/v24/CLI-02/raw/backend-chain.log` |
| CLI-03 | `docs/evidence/v3.0.1/v24/CLI-03/raw/bundled-agent.log` |

## 3. 遗留

| 编号 | 项 | 原因 |
|---|---|---|
| L-CLI-01 | dsh 真实推理 | 需 DeepSeek 官方 key（本机 401） |
| L-CLI-02 | 空目录首次安装 | 已装，重装需再下载 158 MB |
| L-CLI-03 | `dsh tui` profile | 本机只有 headless/web |
| L-CLI-04 | pi 版本漂移 | developer preview |

## 4. 放行口径

本批单列判据：`Ai*Test` 全绿 + 场景 A/B 成功 + 安装器幂等，三条均成立。
整仓 `FAILS=0` 未达成，原因是 v2.3 遗留的第 13 步（Chrome CDP），**与本批改动无关，也不计入本批通过**。

## 修订记录

| 版本 | 日期 | 修订内容 | 授权 |
|---|---|---|---|
| v1.0 | 2026-11-08 | 首版 | 大师「全部授权」 |
