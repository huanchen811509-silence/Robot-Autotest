# v2.4 CLI 多 agent · ④ DEV-PLAN

**文档编号** RA-REL-2026-024-D ｜ **版本** v2.4-cli-agents ｜ **批次** B12（待落号） ｜ **门禁** G11
**状态** 4 包全部完成并自测；真源见 `docs/plan/v2.4-DEV-PLAN-增补草案-待评审-20261108.md`

## 1. 包与状态

| 包 | 内容 | 状态 | 验收读数 |
|---|---|---|---|
| CLI-01 | 候选扩到 7 条 + 三条 argv 模板 | 完成 | `AiCliArgvContractTest` 16 tests |
| CLI-02 | 探测与内置兜底解析 + state 上报 | 完成 | 场景 A/B 真实 HTTP |
| CLI-03 | 内置 pi 安装器 + node 自解析 wrapper | 完成 | `--check` rc=0；wrapper rc=0 / 裸 bin rc=127 |
| CLI-04 | 配置档 + `AI_CLI_ARGS` 注入 | 完成 | 单测两条 |

## 2. 质量门

| 门 | 读数 |
|---|---|
| AI 包单测 | 37 tests / 0 failure |
| 整仓后端 | 1073 tests / 0 failure |
| 前端 | 53 files / 370 tests |
| 编辑器 | 24 files / 219 tests |
| 门禁 | 18 PASS / `FAILS=1`（第 13 步 CDP） |

## 3. 偏差

D-1 候选顺序（pi 从第三改末位）、D-2 node 依赖 127 缺陷、D-3 systemd `Environment` 空格需引号、
D-4 dsh 缺凭据、D-5 内置副本 158 MB、D-6 先验证后编码的顺序说明。详见真源 §4。

## 修订记录

| 版本 | 日期 | 修订内容 | 授权 |
|---|---|---|---|
| v1.0 | 2026-11-08 | 首版 | 大师「全部授权」 |
