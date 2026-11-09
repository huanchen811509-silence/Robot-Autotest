# EW35-05 · 无障碍与跨浏览器实测基线

| 项 | 内容 |
|---|---|
| 命令 | `node scripts/ux-probe.mjs --selfcheck`；`node scripts/ux-probe.mjs` |
| 环境 | 真实 Chrome Headless 157（CDP `:9222`）+ `:8001`；4 路由 × 3 次导航 |
| 证据 | `wcag22-probe.json`、`remediation.md`；对比度机检见门禁 27 步 |
| 结论 | **PARTIAL**：焦点外观 / 200% 缩放 / 一致帮助 PASS；**目标尺寸 FAIL（18 处 <24×24）**；axe 全量与读屏 `BLOCKED` |

## 真实读数

| 项 | 结果 |
|---|---|
| 目标尺寸 24×24 | FAIL，18 处（`/runs` 页 12 处最多） |
| 焦点外观 | PASS（抽检首个 button 的 outline / box-shadow） |
| 200% 缩放无整页横滚 | PASS（4 路由 scrollWidth 均 = 720） |
| 一致帮助入口 | PASS（各页均检出） |
| 键盘可达 / 可访问认证 | PARTIAL（探针只触发 Tab，全站走查缺真人） |
| 拖曳替代 / 避免重复输入 | BLOCKED（需人工键盘与表单任务测试） |
| 跨浏览器 | Chrome PASS（门禁 13 步 48 项）；Firefox / Edge 未安装 → BLOCKED |
| 负向自检 | PASS（注入 16×10 按钮被判红） |

> 本探针是真实 DOM 几何 + 计算样式检查，**不是 axe 全量审计**；WCAG 2.2 AA 最终结论仍需
> axe + 人工键盘 / 读屏走查。已发现的 18 处小目标列为整改项，不得判 PASS。
