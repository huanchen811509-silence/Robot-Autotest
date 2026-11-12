# v5.0.0 正式版本目录清单

| 文件 | 来源 / 作用 |
|---|---|
| `00-RESEARCH.md` | 由 `docs/plan/v5.0.X存量打磨-调研笔记-20261112.md` 复制 |
| `01-ASSESSMENT.md` | 由 `docs/plan/v5.0.X存量打磨-评估报告-20261112.md` 复制 |
| `PRD.md` | 由 `docs/plan/v5.0.X存量打磨-产品PRD-20261112.md` 复制 |
| `DEV-PLAN.md` | 由 `docs/plan/v5.0.X存量打磨-开发计划-20261112.md` 复制 |
| `TEST-PLAN.md` | 由 `docs/plan/v5.0.X存量打磨-测试计划-20261112.md` 复制 |
| `05-TEST-REPORT.md` | 由 `docs/plan/v5.0.X存量打磨-05-TEST-REPORT-20261112.md` 复制 |
| `RELEASE-NOTES.md` | 本版新写：用户价值、兼容性、已知限制、升级与回滚 |
| `MANIFEST.md` | 本文件：目录形态与指针关系 |

## 目录形态与真源约定

- 本目录是 **2026-11-12 13:09 落号时刻的正式版本归档**，六件套内容与该时刻 `docs/plan/` 过程件逐字一致。
- `docs/plan/` 过程件**不删除、不改名**：`tools/check_v50_trace.py`、`tools/check_doc_endpoints.py` 等静态契约脚本以它们为守门对象，
  门禁第 33 / 35 / 36 步依赖这三条路径。落号后过程件冻结为留档，后续修订以本目录为准。
- 每份过程件头部「状态补记」与文末「落号补记」说明 D7 原文与新裁定的关系；**D7 原文保留不删**，冲突处以补记为准。
- 证据不在本目录，统一在 `docs/evidence/v5.0.0/`（20 份，含基线、门禁双读数、TC 证据索引、人工欠账、a11y 清单）。
- 决策记录见 `docs/adr/ADR-50-01.md` ~ `ADR-50-08.md`；口径裁决见 `docs/plan/v5.0-口径裁决清单.md`。
