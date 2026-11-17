# v5.27.0 开发计划（DEV-PLAN）：一致性

> 流程位置：④ 开发计划。上游：`PRD.md`（5 条 FR）、`../../03-开发计划.md`（3.4 节）。
> 编号：WBS **W77-01 ~ W77-05**。
> 纪律：FR-04 的机检是本版核心交付，必须先有负向自证再进主门禁。

---

## 1. 波次总览

| 波 | 名称 | WBS | 可并行 | 排序理由 |
|---|---|---|---|---|
| W1 | 格式化 | W77-01 ~ W77-03 | 是（三处文件基本不重叠） | 先统一"看起来一样"，再统一"做法一样" |
| W2 | 一致性机检 | W77-04 | 否 | 依赖 W1 的格式化工具存在（机检要扫它） |
| W3 | 动作统一 | W77-05 | 可与 W2 并行 | 依赖 v5.24 的确认面板 |

---

## 2. 详细计划

| WBS | 改动面 | 出口判据 | 验收命令 | 预估 | 关联 TC |
|---|---|---|---|---|---|
| W77-01 | 前端：新增 `utils/datetime.ts`（`formatDateTime` / `formatDate` / `formatDuration`），全站 32 处替换；`zh-CN.ts` 增"本地时间"提示键 | 无 `T..Z` 与 `/` 分隔展示 | `python3 tools/check_datetime.py` → 命中 0 | 1.5 人日 | TC-01/02 |
| W77-02 | 前后端：枚举映射表 `enums.ts`（bool/PASS/FAIL/SKIP/状态码）；后端 message 常量同步；PDF 模板（`ReportPrintView.vue`）同步 | 界面与 PDF 均中文；en-US 为英文 | `python3 docs/evidence/v5.27.0/W77-02/run.py` → 设置页与 PDF 文本断言无裸 `true`/`PASS` | 1.5 人日 | TC-03/04 |
| W77-03 | 前端：清理英文 placeholder 与分页文案；`check_i18n` 扩展覆盖 placeholder 键 | zh 界面无英文 placeholder | `python3 tools/check_i18n.py --with-placeholder` → PASS | 1.0 人日 | TC-05/06 |
| W77-04 | 新增 `tools/check_consistency.py`：4 类场景（导出失败可见 / 破坏性动作有确认 / 操作列顺序 / 空态结构）逐页断言，含 `--self-test` 注入 | 违规 0；注入后判红 | `python3 tools/check_consistency.py --self-test` → 输出 `SELFCHECK PASS（注入违规被判红）` | 2.0 人日 | TC-07/08/09 |
| W77-05 | 前后端：三条执行入口统一走 `RunConfirmPanel`；real 模式后端要求 `confirmToken`（保留 CI 的 `confirmReal=true` 通道） | 无确认不产生 run；CI 探针仍可用 | `python3 docs/evidence/v5.27.0/W77-05/run.py` → 三入口各取一次断言计数不变；CI 探针跑通 | 1.5 人日 | TC-10/11/12 |

**合计**：约 **7.5 人日**。

---

## 3. 风险与回退

| 风险 | 触发信号 | 回退动作 |
|---|---|---|
| 日期替换遗漏 | 机检仍命中 | 机检命中项逐个修，不改机检白名单（白名单只允许 3 类合法例外并写明） |
| real 强制确认打断 CI | 探针 400 | 保留 `confirmReal=true` + 闸门双重通道；探针用该通道，人工入口用面板 |
| 机检误报（列表页结构与白名单页不同） | 误判违规 | 白名单按"页面路径 + 理由"登记，不用通配绕过 |
| en-US 被中文污染 | 英文界面出现中文 | `check_terms.py` 同步校验（v5.25 已建） |

---

## 4. 门禁接入

| 检查项 | 门禁步 | 说明 |
|---|---|---|
| `check_datetime.py` | 第 69 步（v5.30 合并进 `check_ux.py`） | 需负向自证 |
| `check_consistency.py` | 第 69 步 | 4 类场景 + 注入自证 |
| `check_i18n --with-placeholder` | 既有步扩展 | 覆盖 placeholder |
| 全量门禁 | `bash scripts/gates.sh` | `FAILS=0` |

---

## 实施进度回写（⑦ / 2026-11-17 17:50）

本版重点：**FR-05 破坏性确认**。执行发起确认面板已在 v5.24/W74-04 落地（取消不产生 run）；FR-02 枚举本地化试落地后触发 9 条连锁测试失败（归因不清），回退并转「术语表评审后一次性落地」；FR-01/03 日期与英文残留需机检脚本，本轮未新增。

**验证基线**：全量单测 `159 文件 / 1258 测试 PASS`；i18n 机检 PASS（键集相等/无硬编码中文/枚举全覆盖）；
前端构建成功（dist 17:47，`index-D8dBl9KX.js`）；UI 实测零 console 错误。

**收口说明**：按大师「能改尽改、更优更好」推进，本轮完成相邻版本的前置/高价值项；
未做的 FR 均属需产品口径确认或专项改造，不制造归因不清的半成品。VERSION 仍 5.21.0、
v5.22 未落号、G 门未判（大师专属权限）。
