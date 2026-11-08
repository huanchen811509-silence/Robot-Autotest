# UI-07 证据：对比度实测（v2.3 / FR-UI-23 / 24 / 25）

对 `theme.css` 的两套语义 token 做真实取色与 WCAG 2.1 相对亮度计算：跑既有守门脚本、复核六预设一致性、并补测守门脚本没有覆盖的两处——暗色三级层（surface / elevated / border）与本次 UI-06 新增的滚动条 thumb。

> 状态：已回填（2026-11-08，命令真实执行，原始日志见 `docs/evidence/v3.0.1/v23/UI-07/raw/`）。本文件所有比值均由日志中的真实输出摘录，无估算。

## 1. 阈值来源（不新设、不放宽）

`tools/check_contrast.py` 文件头与 `CHECKS` 表定义了全部阈值，逐条抄录如下（原文）：

```text
阈值（WCAG 2.1 AA）：
  - 正文 / 表格文字 / 按钮文字：≥ 4.5:1
  - 小号辅助文字（muted）：≥ 4.5:1（它是 12~13px，不享受 3:1 的大字放宽）
  - 状态色文字、边框/图标：≥ 3:1
```

`CHECKS` 里实际生效的阈值分布：19 项按 4.5 或 3.0 判，另有 1 项 `("border", "panel", 1.2, "边框 / 卡片（只要不是完全同色看不见）")`。

这里有一处**既有判据与文件头不一致**，如实记录：文件头写「边框/图标 ≥ 3:1」，但 `border` vs `panel` 实际按 1.2 判（注释说明为「只要不是完全同色看不见」），实测 1.29–1.30:1 之所以算 PASS 就是这个原因。这是本批之前就存在的口径，本次**未修改**（既不放宽也不收紧），改动它会改变门禁结论，超出 UI-07 授权范围。

## 2. 覆盖范围核对：6 个强调色 × 2 主题有没有缺口

```text
$ python3 tools/check_contrast.py
...
[PASS] 14 组（主题 × 强调色）共 280 项对比度全部达标
exit=0
```

14 组 = 2 主题 ×（default + 6 预设 `crimson/blue/gold/orange/violet/jade`），即 **6 个强调色 × 2 主题 12 组，另加 2 个 default 组**，覆盖完整、无缺口。

脚本自身还有三道防假绿断言，本次均未触发：分组数 `< 14` 直接 return 2；每组 token 数 `< 30` return 2；判定项 `< 240` return 2。实测 280 项，与 14 × 20 吻合。

**因此没有扩展 `check_contrast.py`**：覆盖轴（accent × theme）本来就是全的，不需要动；真正的缺口在**判据轴**——20 项固定 token 对里没有「滚动条 thumb」这类新图形元素，也没有「三级层两两之间」。这部分用独立脚本补测（§4、§5），没有塞进门禁，避免擅自扩大门禁口径。

## 3. 20 项判据 × 14 组：最差值取色表

「实测比值」列是 **14 组里归一化后最差的那一项**（比值 ÷ 阈值 最小），括号内标注该值出现在哪一组；阈值列即 §1 的既有阈值。

| 组合 | 前景 | 背景 | 实测比值（最差所在组） | 阈值 | 结论 |
|---|---|---|---|---|---|
| 正文 / 页面底 | `#1a2430` | `#f4f7fb` | 14.60:1（light/blue） | 4.5 | PASS |
| 正文 / 卡片 | `#e6edf3` | `#1a2634` | 12.97:1（dark/blue） | 4.5 | PASS |
| 正文 / 次级底 | `#1a2430` | `#eef2f7` | 13.95:1（light/blue） | 4.5 | PASS |
| 辅助文字 / 页面底 | `#5b6b7c` | `#f4f7fb` | 5.09:1（light/blue） | 4.5 | PASS |
| 辅助文字 / 卡片 | `#8b9bb0` | `#1a2634` | 5.41:1（dark/blue） | 4.5 | PASS |
| 主按钮文字 / 按钮底 | `#ffffff` | `#8f6500` | 5.21:1（light/gold） | 4.5 | PASS |
| 链接与强调文字 / 卡片 | `#8f6500` | `#ffffff` | 5.21:1（light/gold） | 4.5 | PASS |
| 强调文字 / 强调软底 | `#0d7a4f` | `#e7f2ed` | 4.68:1（light/jade） | 4.5 | PASS |
| 成功文字 / 卡片 | `#1c7a4a` | `#ffffff` | 5.34:1（light/blue） | 4.5 | PASS |
| 成功文字 / 成功软底 | `#1c7a4a` | `#e8f7ee` | 4.82:1（light/blue） | 4.5 | PASS |
| 失败文字 / 卡片 | `#f27272` | `#1a2634` | 5.42:1（dark/blue） | 4.5 | PASS |
| 失败文字 / 失败软底 | `#c0392b` | `#fdecec` | 4.76:1（light/blue） | 4.5 | PASS |
| 告警文字 / 卡片 | `#8f5a08` | `#ffffff` | 5.78:1（light/blue） | 4.5 | PASS |
| 告警文字 / 告警软底 | `#8f5a08` | `#fff4dc` | 5.29:1（light/blue） | 4.5 | PASS |
| 取消态色块 / 卡片 | `#6b7c90` | `#1a2634` | 3.58:1（dark/blue） | 3.0 | PASS |
| 图表系列 1 / 卡片 | `#2b7fd4` | `#ffffff` | 4.13:1（light/blue） | 3.0 | PASS |
| 图表系列 2 / 卡片 | `#6b4fd8` | `#ffffff` | 5.62:1（light/blue） | 3.0 | PASS |
| 图表系列 3 / 卡片 | `#1a8a5a` | `#ffffff` | 4.35:1（light/blue） | 3.0 | PASS |
| 图表系列 4 / 卡片 | `#b5651d` | `#ffffff` | 4.34:1（light/blue） | 3.0 | PASS |
| 边框 / 卡片 | `#dde3ea` | `#ffffff` | 1.29:1（light/blue） | 1.2 | PASS |

逐组汇总（每组 20 项）：

| 组 | 判定项 | FAIL 数 | 最低比值 | 最低判据 | 结论 |
|---|---|---|---|---|---|
| dark / default | 20 | 0 | 1.30:1 | 边框 / 卡片 | PASS |
| dark / blue | 20 | 0 | 1.30:1 | 边框 / 卡片 | PASS |
| dark / crimson | 20 | 0 | 1.30:1 | 边框 / 卡片 | PASS |
| dark / gold | 20 | 0 | 1.30:1 | 边框 / 卡片 | PASS |
| dark / orange | 20 | 0 | 1.30:1 | 边框 / 卡片 | PASS |
| dark / violet | 20 | 0 | 1.30:1 | 边框 / 卡片 | PASS |
| dark / jade | 20 | 0 | 1.30:1 | 边框 / 卡片 | PASS |
| light / default | 20 | 0 | 4.76:1 | 失败文字 / 失败软底 | PASS |
| light / blue | 20 | 0 | 4.76:1 | 失败文字 / 失败软底 | PASS |
| light / crimson | 20 | 0 | 4.76:1 | 失败文字 / 失败软底 | PASS |
| light / gold | 20 | 0 | 4.71:1 | 强调文字 / 强调软底 | PASS |
| light / orange | 20 | 0 | 4.76:1 | 失败文字 / 失败软底 | PASS |
| light / violet | 20 | 0 | 4.76:1 | 失败文字 / 失败软底 | PASS |
| light / jade | 20 | 0 | 4.68:1 | 强调文字 / 强调软底 | PASS |

最贴近阈值的一格是 `light / jade`：强调文字坐强调软底 4.68:1，超阈值 1.04 倍。**这是余量最小的地方**，后续再压深 jade 软底会直接破线，记录在此供排期参考。

## 4. 暗色三级层（surface / elevated / border）实测

`theme.css` 里没有字面叫 `--surface` / `--elevated` 的变量，按实际层级映射：**surface = `--bg`（`#0f1720`）**、**elevated = `--panel`（`#1a2634`）**、**border = `--border`（`#29394a`）**。第三层级另附 `--surface-2`（`#141f2b`）作对照。

```text
$ python3 /tmp/contrast_calc.py
== 暗色三级层 ==
dark             bg vs panel  =  1.18 : 1   (#0f1720 / #1a2634)
dark         border vs bg     =  1.53 : 1   (#29394a / #0f1720)
dark         border vs panel  =  1.30 : 1   (#29394a / #1a2634)
dark             s2 vs bg     =  1.08 : 1   (#141f2b / #0f1720)
dark             s2 vs panel  =  1.09 : 1   (#141f2b / #1a2634)
```

| 组合 | 前景 | 背景 | 实测比值 | 阈值 | 结论 |
|---|---|---|---|---|---|
| elevated（`--panel`） vs surface（`--bg`） | `#1a2634` | `#0f1720` | 1.18:1 | 3.0 | 不达标 |
| border（`--border`） vs surface（`--bg`） | `#29394a` | `#0f1720` | 1.53:1 | 3.0 | 不达标 |
| border（`--border`） vs elevated（`--panel`） | `#29394a` | `#1a2634` | 1.30:1 | 3.0 | 不达标 |
| surface-2（`--surface-2`） vs surface（`--bg`） | `#141f2b` | `#0f1720` | 1.08:1 | 3.0 | 不达标 |

按 3:1 判，四级全部不达标。结论：**暗色主题的层级区分不能只靠明度差**，实际靠的是「1px `--border` 描边 + `--shadow`」双保险；`theme.css:133` 的注释也承认这条线是「提到 1.29:1 后才可辨」，与实测 1.30:1 一致。

处理方式：**列入遗留，不调阈值、不改 `theme.css`**（本批禁止改 `theme.css`）。若要让三级层按 3:1 靠明度差自证，需要 `--panel` 提到约 `#2a3a4d` 量级并整体重算 20 项文字判据，属独立批次。

## 5. 本次新增图形元素：滚动条 thumb

UI-06 的细滚动条 thumb 是新增的非文本图形，按 3:1 判（§1 第三条）。三种实际底色都测：

| 组合 | 前景 | 背景 | 实测比值 | 阈值 | 结论 |
|---|---|---|---|---|---|
| thumb `--border-strong` vs panel | `#3a4d61` | `#1a2634` | 1.76:1 | 3.0 | 不达标 |
| thumb `--border-strong` vs bg | `#3a4d61` | `#0f1720` | 2.07:1 | 3.0 | 不达标 |
| thumb `--border-strong` vs surface-2 | `#3a4d61` | `#141f2b` | 1.91:1 | 3.0 | 不达标 |
| thumb `--text-muted` vs panel（暗） | `#8b9bb0` | `#1a2634` | 5.41:1 | 3.0 | 达标 |
| thumb `--text-muted` vs bg（暗） | `#8b9bb0` | `#0f1720` | 6.37:1 | 3.0 | 达标 |
| thumb `--text-muted` vs surface-2（暗） | `#8b9bb0` | `#141f2b` | 5.88:1 | 3.0 | 达标 |
| thumb `--text-muted` vs panel（亮） | `#5b6b7c` | `#ffffff` | 5.47:1 | 3.0 | 达标 |
| thumb `--text-muted` vs bg（亮） | `#5b6b7c` | `#f4f7fb` | 5.09:1 | 3.0 | 达标 |
| thumb `--text-muted` vs surface-2（亮） | `#5b6b7c` | `#eef2f7` | 4.87:1 | 3.0 | 达标 |
| hover `--text` vs panel（暗） | `#e6edf3` | `#1a2634` | 12.97:1 | 3.0 | 达标 |
| hover `--text` vs panel（亮） | `#1a2430` | `#ffffff` | 15.68:1 | 3.0 | 达标 |

据此把 UI-06 的结论从「建议值 `--border-strong`」改成「`--text-muted` 常态 + `--text` 悬停」，理由与实际选择见 `docs/evidence/v3.0.1/v23/UI-06/README.md` §6。

## 6. 六预设一致性（FR-UI-25）

```text
$ python3 tools/check_accent_presets.py
  [INFO] 真源 useAccent.ts ACCENTS = ['crimson', 'blue', 'gold', 'orange', 'violet', 'jade']（6 个）
  [INFO] theme.css 变体块 12 个 / --preset-* 6 个；dialog 6 键；zh/en presets 6/6 键
[PASS] 六预设清单七处一致（6 个预设）
exit=0
```

6 个预设仍在，七处（`useAccent.ts` / `theme.css` 变体块 / `--preset-*` / dialog / zh / en / 生成器）一致。`lint.sh` 里另有 `gen_accent_tokens.py --check` 输出 10/10 组派生主色达标（`crimson/blue/gold/orange/violet/jade` 各 light + dark），本次未改动色板，读数与基线一致。

## 7. 结论与遗留

| 判据 | 状态 | 依据 |
|---|---|---|
| FR-UI-23 两套主题 20 项 token 对照度达标 | 达成 | 14 组 × 20 项 = 280 项，FAIL 0 |
| FR-UI-24 强调色 × 主题全组合覆盖 | 达成 | 6 预设 × 2 主题 + 2 default = 14 组 |
| FR-UI-25 六预设清单七处一致 | 达成 | check_accent_presets PASS |
| 暗色三级层按 3:1 自证层级 | 未达成 | 1.08–1.53:1，见 §4 |

遗留（不粉饰）：

1. 暗色三级层明度差 1.08–1.53:1，未达 3:1；层级靠描边与阴影成立，需独立批次重算 token 才能靠明度自证。
2. `check_contrast.py` 内 `border` vs `panel` 阈值 1.2 与文件头「边框/图标 ≥ 3:1」口径不一致（既有问题，未改）。
3. `light / jade` 强调文字坐软底 4.68:1，余量仅 1.04 倍，最脆弱的一格。
4. 三级层与滚动条 thumb 两组判据只在本证据里成立，**未接进门禁**，后续改动不会被自动拦截。
