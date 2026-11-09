# WCAG 2.2 目标尺寸整改清单（真实探针输出）

| 项 | 内容 |
|---|---|
| 来源 | `wcag22-probe.json`（真实 Chrome 157 / CDP / DOM 几何） |
| 判据 | WCAG 2.2 AA 2.5.8：可见交互目标最小 24×24 CSS px |
| 状态 | **待整改**，不得判 PASS；整改后须复跑 `node scripts/ux-probe.mjs` 并留同批次读数 |

| 路由 | 元素 | id | 尺寸 | 文本 |
|---|---|---|---|---|
| /workbench | SELECT | — | 128×21 | 选择项目演示项目-Pytest内核验证项 |
| /workbench | SELECT | — | 26×21 | 中文EN |
| /runs | SELECT | — | 128×21 | 选择项目演示项目-Pytest内核验证项 |
| /runs | SELECT | — | 26×21 | 中文EN |
| /runs | BUTTON | — | 56×14 | 查看日志 |
| /runs | BUTTON | — | 56×14 | 查看日志 |
| /runs | BUTTON | — | 56×14 | 查看日志 |
| /runs | BUTTON | — | 56×14 | 查看日志 |
| /runs | BUTTON | — | 56×14 | 查看日志 |
| /runs | BUTTON | — | 56×14 | 查看日志 |
| /runs | BUTTON | — | 56×14 | 查看日志 |
| /runs | BUTTON | — | 56×14 | 查看日志 |
| /runs | BUTTON | — | 56×14 | 查看日志 |
| /runs | BUTTON | — | 56×14 | 查看日志 |
| /assets | SELECT | — | 128×21 | 选择项目演示项目-Pytest内核验证项 |
| /assets | SELECT | — | 26×21 | 中文EN |
| /nodes | SELECT | — | 128×21 | 选择项目演示项目-Pytest内核验证项 |
| /nodes | SELECT | — | 26×21 | 中文EN |

## 整改方向（不在本轮擅自改实现）

1. `/runs` 页 12 处为密集表格行操作与分页控件，优先统一行内图标按钮的最小尺寸与内边距。
2. 保留语义与快捷键不变；只在设计令牌层调整 `min-width` / `min-height`，不改断言、不删控件。
3. 整改后复跑探针 + 门禁 13/27 步；若影响包体或对比度，按门禁 21/27 步裁决。
