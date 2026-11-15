# v5.10.0 需求调研（00-RESEARCH）：向导到底缺什么

> 流程位置：① 需求调研。每条结论标注**证据来源**；推断与实测分开写。
> 上游：`docs/plan/重构评估-20261115/01-信息架构与导航评估.md` §3、`06-数据治理与开发隔离评估.md` §3。
> 本文件不改结论，只补 **v5.9.0 交付后**的实测事实（5.9 把判权与幂等端点补齐了，5.10 才谈得上「关联」）。

## 1. 实测事实（2026-11-15，仓库 `f0e40dc`）

> 标注口径：**[已核实]** = 本轮读过代码/查过库；**[推断]** = 由事实推出未实测；**[假设]** = 待验证前提。

| # | 事实（三档标注） | 证据 |
|---|---|---|
| R-1 | [已核实] 项目向导只有 6 步：`template/basics/runtime/members/content/review`，**没有用户组步、没有凭证选择** | `frontend/src/views/ProjectWizardView.vue` `ALL_STEPS` |
| R-2 | [已核实] 向导是「建项目 → 批量加成员 → 应用内容」三连调用，任一步失败留下半成品 | 同上 `createProject()`；`POST /projects`、`/members/batch`、`/apply-content` 三个端点 |
| R-3 | [已核实] 项目编号已用 `project_code_sequence` 原子分配，但**序列没有存量回填** | `ProjectRepositoryJdbc#nextCodeSequence`（V11_43 建表）；升级前项目不在序列里 |
| R-4 | [已核实] `nextCode` 撞号重试 **100 次** | `ProjectService#nextCode` 循环上限 |
| R-5 | [已核实] 模板编号按 `MAX(序号)+1` 取（`project_template` 内自算），删掉最大号会复用同号 | `ProjectTemplateService#generateCode` |
| R-6 | [已核实] `project_template` **已有** `kernel` 列；场景明细由 `project_template_item(item_type='SCENARIO')` 承载 | 真实库 `information_schema`：kernel varchar(16)；`idx_template_item(template_id,item_type,sort_no)` |
| R-7 | [已核实] 没有「新建模板向导」页，模板只能在一屏表单里建 | `frontend/src/views/global/GlobalTemplatesView.vue`，无 `/g/templates/new` 路由 |
| R-8 | [已核实] 成员/组授权端点已幂等（5.9 补的），组授权要求管理员 | `POST /groups/grant` → `GroupService#grant`（`ON DUPLICATE KEY UPDATE`） |
| R-9 | [已核实] 设备表已有两个凭证列 `management_credential_id` / `bmc_credential_id`，但**没有绑定接口**（只有采集时按凭据反查设备） | `V11_45`；`GlobalAssetService` 只有 `devicesOfCredential`，无 bind |
| R-10 | [已核实] 资产/用例导入是**两步弹窗**（预览 → 提交），不是分步向导 | `GlobalAssetsView.vue` 的 `preview/commit` 两个 API 调用 |

## 2. 由事实推出的三条结论（**[推断]**，未实测）

1. **「关联」缺的不是控件而是落点**：用户组、凭证、负责人在库里都有列/表，缺的是「向导里能选 + 一次写进去」。因此本版的价值集中在 `POST /projects/composite` 一个端点上，UI 只是它的六个分组。
2. **编号必须先在服务端立住**：向导第 8 步要显示「真编号」，而前端本地推算（`nextProjectCode`）在私有项目不可见时会漏号。索引与序列回填不先做，模板向导的「前缀冲突」也无从判定。
3. **模板向导与项目的区别是「前缀」**：模板是项目的母版，编码前缀决定项目编号空间。两个模板共用一个前缀**不会撞号**（序列按 scope 分段），但会让人分不清编号出处——所以是「人可读性」问题，不是数据完整性问题。这条决定了冲突的处置是硬拒还是提示。

## 3. 未核实项（不写成结论）

| # | 待核 | 为什么没核 |
|---|---|---|
| U-1 | [假设]  真实浏览器里八步向导的可用性（键盘操作、步间滚动位置） | 本版无浏览器自动化环境；只做了视图级单测 |
| U-2 | [假设]  双内核模板在项目侧的切换路径 | 双内核属 5.11 的执行分派范围，本版未实现 |
| U-3 | [假设]  导入五步在 1000 行 CSV 上的映射性能 | 导入五步未在本版实施（见 TEST-REPORT 降级项） |

## 4. 与评估报告的差异

评估 01 §3 把「项目向导补用户组/凭证」列为 **P0 流程体验**，本版按 P0 处理；
评估 06 §3 提到「演示标记由向导一键带出」，本版落在属性步一个开关上（`project.is_demo`），
不新增第二个写入口。
