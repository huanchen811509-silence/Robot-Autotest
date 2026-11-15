# v5.10.0 测试报告（05-TEST-REPORT）

> 流程位置：⑥ 测试执行 → 结果落定。数据源：`TEST-PLAN.md` 的 50 条 TC + `DEV-PLAN.md` §10.1 执行状态。
> **不新造用例**：TC 编号与 TEST-PLAN 一一对应，逐条写结论与证据形态。

## 1. 读数总览

| 项 | 读数 | 证据 |
|---|---|---|
| 后端全量单测 | 见 §4（本版新增 11 条 + 既有全量） | `mvn -o -B test` surefire 汇总 |
| 前端全量单测 | 133 文件 / 1062 例 0 失败 | `npx vitest run` |
| 类型检查 | `vue-tsc --noEmit` 0 error | 同上 |
| 接口契约 | 448 endpoints / 367 paths，`--check` PASS | `tools/gen_api_contract.py` |
| 导航机检 | 45 叶 / 孤儿 0 / 格式合法 PASS | `tools/check_nav_kind.py` |
| 迁移 | `V11_68` 在 `robot_dev` 实跑 + 幂等复跑通过；回填判据 OK | §3 |

## 2. 逐条结论（TC-v5.10-01 ~ 50）

| TC | 结论 | 证据 / 说明 |
|---|---|---|
| 01 | PASS | 全局「项目」组 `global-projects-new` 指向 `/g/projects/new`；`navigation.spec` 叶数断言含本项 |
| 02 | PASS | 项目树 `workbench-create` 仍指 `/projects/new`，两处同一组件 |
| 03 | PASS | 菜单/标题/i18n 统一「项目向导」；`grep 新建项目` 在 config 与 locales 仅剩注释与测试名 |
| 04 | PASS | `check_nav_kind.py` 45 叶 PASS；`navigation.spec.ts` 61 条全绿 |
| 05 | PARTIAL | 视图级单测覆盖提交 payload；真实 HTTP 建项目未跑（无运行中后端） |
| 06 | PASS | `ProjectWizardView.spec`「用户组步：勾选组并选角色」 |
| 07 | PARTIAL | 单测验到 `composite.groups` 落库请求；`project_group_role` 实表核对未跑 |
| 08 | PASS | `GroupService#grant` 为 `ON DUPLICATE KEY UPDATE`（幂等，v5.9 已验） |
| 09 | PASS | 不选组时 `groups: []`，提交照常 |
| 10 | PASS | 步内渲染「该组已有 {count} 名成员」，取 `GroupRow.memberCount` |
| 11 | PASS | 内容步为每台设备渲染 `wizard-cred-mgmt/bmc-{id}` 两个选择器 |
| 12 | PARTIAL | payload 已带两个凭证 id；`device.management_credential_id` 实库核对未跑 |
| 13 | PARTIAL | 候选取自 `/global/assets/{id}/credentials/options`（后端按平台/可见项目过滤）；跨项目不泄露由后端保证，未做真实响应比对 |
| 14 | PASS | 留空 → 两面 `null`（后端语义「不动」，不清既有绑定） |
| 15 | PASS | 向导不调采集/验证端点；`credential_verification` 无写入路径（代码走查） |
| 16 | PASS | 未选负责人 → `stepProblem` 拦截；服务端 `create(ownerId)` 404 兜底 |
| 17 | PARTIAL | `ownerId` 随 composite 提交；`owner_id` 与成员行同事务由服务实现，实库核对未跑 |
| 18 | PASS | 勾选 → `demo: true`（单测断言请求体） |
| 19 | PASS | 默认 `demo: false` |
| 20 | PASS | `TemplateWizardView.spec`「五步可达」「可回退」 |
| 21 | PARTIAL | 明细分四类落 `items`（单测断言）；创建后 `GET /templates/{id}` 逐类计数未跑 |
| 22 | PASS | 前缀被占用 → 复核步点名 + 提交按钮 disabled（单测） |
| 23 | PASS | 空模板须勾「这是一个空模板」才能提交（单测） |
| 24 | PASS | 重名 → 提示但放行（D-2 裁决；单测覆盖提示文案存在） |
| 25 | BLOCKED | 需真实列表 + 项目向导第 1 步联动，无运行中环境 |
| 26 | PASS | 场景标签可加可删，落 `SCENARIO` 明细（单测断言 items） |
| 27 | PARTIAL | 明细写入由后端 `insertItems` 承担；`scenarios` 实表行数未核 |
| 28 | 未达成 | 模板内核**未带出**到项目向导（项目向导第 1 步仍是静态模板卡）。见 §5 降级项 D-1 |
| 29 | 未达成 | 双内核模板未实现（属 5.11 执行分派） |
| 30 | 未达成 | `WizardContentPicker` 未统一为三类来源，两向导各用现状能力。见 D-2 |
| 31 | BLOCKED | 依赖 30 |
| 32 | BLOCKED | 依赖 30（原子快照语义未变，既有用例集展开仍按 id 展开） |
| 33 | BLOCKED | 依赖 30 |
| 34 | PARTIAL | `ProjectCodeSequenceTest`「取号只增不减」证明序列口径；「删 -003 再建得 -004」需真实库 |
| 35 | BLOCKED | 5 并发建项目需运行中后端；序列原子性由 `uk_project_code_sequence_scope` + `LAST_INSERT_ID` 保证 |
| 36 | PASS | `ProjectTemplatePrefixTest`「前缀重复被拒」点名占用方 |
| 37 | PASS | `V11_68` 已在 `robot_dev` 实跑：`PRJ-RF-202611` 序列 2 ≥ 存量最大号 2；幂等复跑值不变 |
| 38 | PARTIAL | 复核步显示服务端序列返回的真号（`/projects/next-code`），不是本地推算；与创建后实际编号比对未跑 |
| 39 | 未达成 | 资产导入仍为两步弹窗。见 D-3 |
| 40 | BLOCKED | 依赖 39 |
| 41 | BLOCKED | 依赖 39 |
| 42 | PASS | 全局组数 6、叶数 20；`check_navigation.py` / `navigation.spec` 双绿（**未新增一级菜单**） |
| 43 | PASS | 导入记录入口 `?view=imports` 未被本版改动 |
| 44 | PARTIAL | 架构上「前六步只在前端持有状态」，无写请求；「库零新增」的真实计数未跑 |
| 45 | PASS | 提交失败留在复核步，结果卡片不出现（单测「提交失败不落在半成品上」） |
| 46 | PASS | `ProjectCompositeServiceTest`「凭证绑定失败降级」「成员失败降级」；异常路径不产生结果卡 |
| 47 | PARTIAL | 一个事务由 `@Transactional` 保证；故障注入的真实回滚未跑（仅单测级） |
| 48 | 未达成 | 演示项目（2 集/10 例/1 报告）未造。见 D-4 |
| 49 | BLOCKED | 依赖 48 |
| 50 | BLOCKED | 依赖 48 |

**统计**：PASS 24 / PARTIAL 13 / BLOCKED 9 / 未达成 4。

## 3. 关键读数与证据

```
# 前端
Test Files  133 passed (133)
     Tests  1062 passed (1062)

# 契约
endpoints=448 paths=367
PASS API 契约快照与控制器源码一致

# 导航
OK  孤儿叶子 = 0（45 个入口全部有路由）
OK  叶子数 = 基线 45

# 迁移回填（robot_dev）
BEFORE  projects 16 / seq 2
AFTER   prefix 5 行（API PRJ PY UI TPL）
判据    PRJ-RF-202611 maxno=2 next_no=2 → OK
幂等    二次执行 next_no 不变
```

## 4. 本版新增后端单测

`ProjectCodeSequenceTest`（3）：序列只增不减 / 撞号封顶 3 次且 409 / 中间撞一次后成功。
`ProjectTemplatePrefixTest`（3）：前缀重复被拒并点名 / 自动编号不判唯一 / 首用自动登记。
`ProjectCompositeServiceTest`（5）：六类落地计数 / 成员失败降级 / 非管理员组授权 403 / 凭证绑定失败降级 / 空请求 400。

## 5. 降级与未达成（逐条写明理由与授权口径）

| # | 项 | 状态 | 理由 | 后续 |
|---|---|---|---|---|
| D-1 | 模板内核带出到项目向导（W60-11 / FR-06 判据 3-4） | 未达成 | 项目向导第 1 步用的是静态模板卡 `PROJECT_TEMPLATES`，改成拉真模板并把 `kernel` 传下去要改第 1、3 步的联动；本版改动窗口已用尽 | 5.11 执行分派落地时一并做（内核选择本就要重写） |
| D-2 | `WizardContentPicker` 三类来源统一（W60-12） | 未达成 | 与 5.11 用例清单三栏共用树组件，提前改等于两次返工（DEV-PLAN 风险 R4） | 5.11 |
| D-3 | 资产/用例导入五步（W60-13/14） | 未达成 | 字段映射的正确性只在真实 CSV 上可验；本版无采样数据，做了也是空壳（评估 §4） | 5.12 资产工作台 |
| D-4 | 向导演示项目与 purge 覆盖（W60-17） | 未达成 | 需要运行中后端造 13 个对象；本版只完成 `is_demo` 写入路径 | 本版收尾或 5.11 |
| D-5 | `V11_69`（模板场景与内核） | **撤销** | 内核列已存在、场景由 `project_template_item(SCENARIO)` 承载、模板编号已有唯一键——无结构可加，不造空迁移 | — |
| D-6 | 前缀「未注册即拒」 | **改口径** | 改为「首用登记 + 占用才拒」，理由见 01-ASSESSMENT §3 | 已写进 DEV-PLAN 推翻项 A1 |

## 6. 未闭合但不阻塞

- 真实 HTTP 与真实浏览器两类未跑（无运行中后端 / 无浏览器自动化）。
  这两类是本版 PARTIAL 的主要来源，**不视为通过**。
- 交付口径维持：本版**未发版、未部署、未推远程**，由大师在统一收口时决定。
