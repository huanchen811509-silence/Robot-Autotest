# 5.0.X · 键盘 / 读屏 / 缩放人工走查（W50-19）

| 项 | 内容 |
|---|---|
| 日期 | 2026-11-12（GMT+8） |
| 状态 | **NOT-RUN**（未执行） |
| 阻塞 | D1 裁定 CDP `:9222` 保持取消，9 支体验探针复用该通道；人工值守未安排 |
| 所需条件 | CDP 恢复或独立浏览器环境、人工值守、逐页记录、读屏器 |

## 已落地的静态准备（不等于人工验收）

- `frontend/src/styles/target-size.css` 补了 `:focus-visible` 焦点环（`2px solid currentColor`），不写死 hex。
- `tools/check_v50_contrast_extra.py` 静态守护 select / button / tabindex / focus-visible 四类规则存在。
- 原 280 项对比度机检由 `tools/check_contrast.py` + 门禁 27 步守护，本版未删任何原项。

## 走查项（待人工执行，逐页记录）

| 编号 | 项 | 判据 | 状态 |
|---|---|---|---|
| KW-01 | 焦点外观 | 焦点环可见、与背景 ≥3:1 | NOT-RUN |
| KW-02 | 200% 缩放 | 无整页横滚、无内容丢失 | NOT-RUN |
| KW-03 | 一致帮助入口 | 各页位置一致、Tab 可达 | NOT-RUN |
| KW-04 | Top8 Tab 顺序 | 八条任务逐页顺序合理、无焦点陷阱 | NOT-RUN |
| KW-05 | 读屏朗读 | 控件名 / 状态 / 结果可读 | NOT-RUN |

**纪律**：静态机检 PASS 不得折算成上述人工项 PASS；机检只证明规则存在，不证明真实渲染达标。
