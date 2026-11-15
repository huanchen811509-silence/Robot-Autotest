# v5.10.0 开发计划（DEV-PLAN）：项目与模板向导 —— 编号先立，向导再补

> 流程位置：④ 开发计划。**无开发计划不许开工编码**。
> 上游：`PRD.md`（11 FR + 7 NFR）、`01-信息架构与导航评估.md` §3、`06-数据治理与开发隔离评估.md` §3、v5.9.0 交付口径。
> 本文件给 WBS 编号、改动面与**可复制的验收命令**；不给「大约几天」，只给依赖关系与可独立验收的边界。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|
| W1 | 编号基座 | W60-01 ~ W60-03 | 是 | 必须最先（向导第 8 步要显示真编号） |
| W2 | 项目向导补步与事务 | W60-04 ~ W60-07 | 是 | 依赖 W1、5.9 的幂等端点与组判权 |
| W3 | 模板向导 | W60-08 ~ W60-11 | 是 | 依赖 W1（前缀注册）；与 W2 并行 |
| W4 | 组件统一与导入向导 | W60-12 ~ W60-14 | 是 | 依赖 W2/W3 的内容步存在 |
| W5 | 入口与门禁 | W60-15 ~ W60-16 | 是 | 依赖 W2/W3 页面可跑（先加菜单会点开空壳） |
| W6 | 演示覆盖验证 | W60-17 | 是 | 依赖 v5.9 的 `purge-demo.sh` 与 W2 的演示标记 |
| W7 | 收口 | W60-18 ~ W60-19 | 否（最终波） | 依赖 W1 ~ W6 全部出口判据 |

**并行纪律**：W1 是硬前置（编号不复用是 P1 判据，但第 8 步显示真编号属 P0 流程体验，不做完 W1 无法验收 FR-01-⑤）；
W2 与 W3 改动面不重叠（`ProjectWizardView` vs `TemplateWizardView`）可并行；W5 必须在 W2/W3 之后。

**本版迁移 2 支**（`V11_68` / `V11_69`）。**PRD 遗留 L-60-03 本文件定稿**：模板名称**允许重名**，但复核步必须确认（与空模板确认同一机制）；**编码前缀唯一为硬拒**（返回 400 并点名前缀）。

---

## 2. W1 编号基座（W60-01 ~ W60-03）

### W60-01 `V11_68` 序列表与前缀注册表

| 项 | 内容 |
|---|---|
| 目标 | `project_code_sequence(prefix, kernel, yyyymm, seq)` 唯一键 + `project_code_prefix(code, meaning, owner)`；含存量回填（既有项目最大号写入序列） |
| 改动面 | `db/migration/V11_68__code_sequence.sql` + `.down.sql`；回填 SQL 单独一份进证据目录 |
| 出口判据 | ① 空库与既有库各跑一次成功；② 回填后 `MAX(project.code 流水)` = 序列值；③ down 后两表 DROP 且 `project.code` 不受影响 |
| 验证命令 | `psql "$DB_URL" -c "select prefix,kernel,yyyymm,max(seq) from project_code_sequence group by 1,2,3 order by 1,2,3"` |
| 依赖 | — |

### W60-02 `nextCodeSequence` 改序列 + 重试

| 项 | 内容 |
|---|---|
| 目标 | 去掉 `MAX(序号)+1`（`ProjectService.java:191-196`），改「序列原子递增 + `DuplicateKeyException` 重试 3 次」；极端失败返回 409 人话，不返回 500 |
| 改动面 | `ProjectService.nextCodeSequence`、`ProjectCodeSequenceRepository`（新增）、单测（含并发） |
| 出口判据 | ① 删最大号后新建 → 新号 = 原号 +1；② 5 并发全部成功且编号互不相同；③ 重试耗尽路径有单测且返回 409 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ProjectCodeTest,ProjectCodeConcurrencyTest test` |
| 依赖 | W60-01 |

### W60-03 前缀注册校验

| 项 | 内容 |
|---|---|
| 目标 | 两个模板用同一前缀 → 拒绝并点名前缀；前缀必须存在于注册表 |
| 改动面 | `ProjectTemplateService` 创建/更新路径 + `ProjectCodePrefixRepository` + 单测 |
| 出口判据 | ① 重复前缀返回 400 且 `message` 含前缀值；② 未注册前缀被拒；③ 既有模板前缀在回填时全部入表（计数比对） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ProjectTemplateServiceTest test` |
| 依赖 | W60-01 |

---

## 3. W2 项目向导补步与事务（W60-04 ~ W60-07）

### W60-04 「用户组」步

| 项 | 内容 |
|---|---|
| 目标 | 第 5 步：多选用户组 + 每组建角色；写入走 `POST /groups/{id}/grant`（5.9 幂等）；步内显示「该组已有 N 名成员」 |
| 改动面 | `ProjectWizardView.vue`（新增 step key `groups`）、`allocGroups` 状态、`api/iam.ts` 调用、spec |
| 出口判据 | ① 组列表全部可见可多选；② 创建后 `project_group_role` 有行且组内成员 `scope=mine` 可见；③ 重复选择 `added=0 skipped=1` 不报错；④ 不选组也能下一步 |
| 验证命令 | `cd frontend && npx vitest run src/views/ProjectWizardView.spec.ts -t 用户组` |
| 依赖 | W60-07（提交事务） |

### W60-05 资产步内联凭证

| 项 | 内容 |
|---|---|
| 目标 | 每台选中设备可内联选「管理面凭证 / BMC 凭证」，可留空；写 `device.management_credential_id` / `bmc_credential_id` |
| 改动面 | `ProjectWizardView.vue` 资产步、凭证下拉（只列本项目可见）、`api/credentials.ts`、spec |
| 出口判据 | ① 两选择器可见可留空；② 选后 DB 两列等于所选 id；③ 下拉不含跨项目凭证；④ 反证：`credential_verification` 行数不因向导增加 |
| 验证命令 | `cd frontend && npx vitest run src/views/ProjectWizardView.spec.ts -t 凭证` |
| 依赖 | W60-07 |

### W60-06 属性步负责人与演示标记

| 项 | 内容 |
|---|---|
| 目标 | 第 3 步补负责人（必填，默认当前用户）与「标记为演示项目」开关（默认关） |
| 改动面 | `ProjectWizardView.vue`、`POST /projects` 请求体扩字段、服务端必填校验、spec |
| 出口判据 | ① 不填负责人前端拦截 + 服务端 400；② `project.owner_id` 与 `project_member` 行同事务写入；③ 勾选 → `is_demo=1`，不勾 → 0 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ProjectServiceTest test` |
| 依赖 | W60-07、v5.9 FR-11 |

### W60-07 一次提交事务（方案 A）

| 项 | 内容 |
|---|---|
| 目标 | 前七步仅前端持有状态，第 8 步一次性创建（项目 + 成员 + 组授权 + 资产 + 凭证绑定 + 内容关联）于一个事务 |
| 改动面 | 新增 `POST /projects/composite`（或 `POST /projects` 扩展 + 编排服务）、前端提交层、退出确认、故障注入单测 |
| 出口判据 | ① 走完 7 步不提交 → 库零新增；② 提交失败 → 各步状态保留可重试；③ 注入成员写入异常 → 项目也不存在（整体回滚）；④ 已填必填项时关闭有确认弹窗 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ProjectCompositeCreateTest test` |
| 依赖 | W60-02 |

---

## 4. W3 模板向导（W60-08 ~ W60-11）

### W60-08 `V11_69` 模板场景与内核

| 项 | 内容 |
|---|---|
| 目标 | 模板表补内核列（若缺）与 `scenarios` 关联（若 `template_scenario` 已存在则只补索引） |
| 改动面 | 迁移两支；`ProjectTemplateService.TemplateDetail` 若字段名不一致需对齐 |
| 出口判据 | ① 迁移幂等（`IF NOT EXISTS`）；② 四类明细 + 资产 + 场景一次读全；③ down 后回到 `V11_68` |
| 验证命令 | `psql "$DB_URL" -c "\d project_template"` |
| 依赖 | W60-01 |

### W60-09 模板向导五步页

| 项 | 内容 |
|---|---|
| 目标 | `/g/templates/new` 五步：基本信息 / 内核与场景 / 内容明细 / 资产 / 冲突复核；步态三态、可回退 |
| 改动面 | 新增 `views/global/TemplateWizardView.vue` + 路由 + 5 份 spec |
| 出口判据 | ① 五步可达可回退；② 创建的模板四类明细与所选逐类计数一致；③ 列表可见且可被项目向导第 1 步选中 |
| 验证命令 | `cd frontend && npx vitest run src/views/global/TemplateWizardView.spec.ts` |
| 依赖 | W60-08、W60-12 |

### W60-10 复核步三类冲突

| 项 | 内容 |
|---|---|
| 目标 | 点名「编码前缀冲突 / 模板重名 / 空模板」；前缀冲突硬拒；重名与空模板需勾选确认 |
| 改动面 | `TemplateWizardView` 复核步 + 后端校验（前缀 400、重名放行但返回 `warnings`） |
| 出口判据 | ① 前缀冲突禁止提交且点名；② 空模板不勾确认不能提交；③ 重名可通过但响应含 `warnings`；④ 三类各一张截图 |
| 验证命令 | `cd frontend && npx vitest run src/views/global/TemplateWizardView.spec.ts -t 复核` |
| 依赖 | W60-09 |

### W60-11 模板内核带出到项目向导

| 项 | 内容 |
|---|---|
| 目标 | 选 pytest 模板 → 项目向导运行时步默认内核 pytest；双内核模板不锁死 |
| 改动面 | `ProjectWizardView` 第 1 步 → 第 3 步的内核传递 + spec |
| 出口判据 | ① 单内核模板带出对应内核；② 双内核模板允许切换；③ 截图各 1 张 |
| 验证命令 | `cd frontend && npx vitest run src/views/ProjectWizardView.spec.ts -t 内核` |
| 依赖 | W60-09 |

---

## 5. W4 组件统一与导入向导（W60-12 ~ W60-14）

### W60-12 `WizardContentPicker` 三类来源统一

| 项 | 内容 |
|---|---|
| 目标 | 扩为「用例集 / 用例 / 场景」三类，两个向导共用同一组件；用例集选择为**原子快照** |
| 改动面 | `components/WizardContentPicker.vue`、两个向导的 import、spec |
| 出口判据 | ① 代码检查两处 import 同一文件；② 3 集 + 5 例 + 2 场景 → 复核步显示 10 项；③ 建后改源集成员，项目成员不变（快照）；④ 全选/清空/搜索行为一致 |
| 验证命令 | `cd frontend && npx vitest run src/components/WizardContentPicker.spec.ts` |
| 依赖 | W60-07 |

### W60-13 资产导入五步向导

| 项 | 内容 |
|---|---|
| 目标 | `GlobalAssetsView` 两步弹窗 → 五步（选文件 / 字段映射 / 冲突预览 / 提交 / 结果）；**入口仍在操作列** |
| 改动面 | 新增 `components/ImportWizard.vue`（资产配置）、`GlobalAssetsView.vue`、spec |
| 出口判据 | ① 五步可回退，映射页可改列对应；② 预览「新增 N / 更新 M / 冲突 K」与提交结果一致；③ 导航组数叶数不变 |
| 验证命令 | `cd frontend && npx vitest run src/components/ImportWizard.spec.ts` |
| 依赖 | W60-12 |

### W60-14 用例导入五步向导

| 项 | 内容 |
|---|---|
| 目标 | 公共用例导入同样升级五步；结果仍进「导入记录」（`?view=imports`） |
| 改动面 | `GlobalCasesView`（或 `CasesView` 全局侧）导入入口、复用 `ImportWizard`、spec |
| 出口判据 | ① 五步可走通；② 结果可在导入记录查到；③ 中断后重进能看到上次批次状态 |
| 验证命令 | `cd frontend && npx vitest run -t 导入向导` |
| 依赖 | W60-13 |

---

## 6. W5 入口与门禁（W60-15 ~ W60-16）

### W60-15 两个二级项与叶数断言

| 项 | 内容 |
|---|---|
| 目标 | 全局「项目」组与项目「项目」组各加「项目向导」；全局「模板」组加「模板向导」；文案 `/新建项目/` 在菜单与标题处零命中 |
| 改动面 | `navigation.ts`、`navigation.spec.ts` 叶数断言、`check_navigation.py` 的 `GLOBAL_EXPECTED`（组数不变，叶数变） |
| 出口判据 | ① `check_navigation.py` PASS 且叶数读数正确；② `npm test` 全绿；③ grep `新建项目` 在菜单与页面标题零命中 |
| 验证命令 | `python3 tools/check_navigation.py`；`cd frontend && grep -rn "新建项目" src/config src/views --include=*.vue --include=*.ts \| wc -l` |
| 依赖 | W60-09、W60-16 |

### W60-16 文案与 i18n 收敛

| 项 | 内容 |
|---|---|
| 目标 | 两向导全部文案中英齐备；菜单项 / 页面标题 / i18n 三处一致 |
| 改动面 | `locales/{zh,en}.json`、两向导模板内硬编码中文清理 |
| 出口判据 | ① `check_i18n.py` PASS（或等价脚本）；② 抽检 10 条文案中英对应；③ 无硬编码中文（grep 中文在 `<template>` 文本节点） |
| 验证命令 | `cd frontend && npx vitest run -t i18n` |
| 依赖 | W60-09 |

---

## 7. W6 演示覆盖验证（W60-17）

### W60-17 向导演示项目与 purge 覆盖

| 项 | 内容 |
|---|---|
| 目标 | 用向导创建一个演示项目（2 用例集 / 10 用例 / 1 报告）→ `purge-demo.sh` dry-run 清单含这 13 个对象 |
| 改动面 | 无代码；`docs/evidence/v5.10.0/demo-coverage/` |
| 出口判据 | ① 13 个对象全部出现在清单；② dry-run 前后计数一致；③ 真实项目不出现在清单 |
| 验证命令 | `bash scripts/ops/purge-demo.sh --dry-run \| grep -c "WIZ-DEMO"` |
| 依赖 | W60-06、v5.9 FR-10 |

---

## 8. W7 收口（W60-18 ~ W60-19）

### W60-18 契约与迁移收口

| 项 | 内容 |
|---|---|
| 目标 | `openapi.json` 重生成；两支撑上迁移 up/down 演练 |
| 改动面 | 契约快照（WSL 内重生成，不拷 E 盘旧件） |
| 出口判据 | ① `--check` PASS；② `endpoints/paths` 读数写进测试报告；③ down 后 schema 与 `V11_67` 一致 |
| 验证命令 | `cd backend && python3 tools/gen_api_contract.py --check` |
| 依赖 | W60-07、W60-09 |

### W60-19 门禁全量与文档回写

| 项 | 内容 |
|---|---|
| 目标 | `GATES_RESULT FAILS=0`；六份齐全；总纲状态回写 |
| 改动面 | `docs/release/v5.10.0/*`（从 `_draft/` 迁入）、总纲交付表 |
| 出口判据 | ① 门禁 `FAILS=0`；② `check_release_docs.py v5.10.0` PASS；③ 表格机检全 PASS |
| 验证命令 | `python3 tools/check_gates.py 2>&1 \| tail -3` |
| 依赖 | W1 ~ W6 全部 |

---

## 9. 接口与数据变更清单

| 变更 | 类型 | 影响 | 处理 |
|---|---|---|---|
| `POST /projects` 扩字段（owner / isDemo / groups / assets / credentials / contents） | 请求扩展 | 一次性提交需服务端编排 | 契约重生成 |
| 新增 `POST /projects/composite`（若 POST 扩展不可行） | 新端点 | 向导唯一写入口 | 契约重生成 |
| `POST /projects/{id}/assets` 带凭证绑定字段 | 请求扩展 | 只做显式选择 | 契约重生成 |
| 模板创建返回 `warnings` | 响应新字段 | 重名确认用 | 契约重生成 |
| `V11_68` / `V11_69` | 2 支迁移 | 序列表、前缀表、模板内核/场景 | 每支配 `.down.sql` |

---

## 10. 风险与应对

| # | 风险 | 应对 |
|---|---|---|
| R1 | 八步向导在一个事务里写六类对象，任一失败全回滚 | 先做 W60-07 编排层再做各步 UI；故障注入四类（成员/组/资产/内容）各一条 |
| R2 | 编号序列回填算错导致撞号 | 回填写入独立 SQL 并断言「序列值 = 存量最大号」；并发用例必跑 |
| R3 | 模板向导与既有一屏表单并存造成两套写入口 | W60-15 要求旧内联表单下线或改为跳转；代码检查无第二处 `POST /templates` 表单提交 |
| R4 | 内容选择器在 5.11 还要换树组件，本版重复实现 | 本版只留接口（L-60-01），不写第三套分类渲染 |
| R5 | 导入向导升级影响既有批次记录 | 结果页仍走 `?view=imports`，不改批次表 |

---

## 11. 与相邻版本的边界

1. 本版**不做**凭证自动匹配（5.13）、**不做**用例清单三栏（5.11）、**不做**资产工作台合并（5.12）。
2. 本版复用 5.9 的三源并集与幂等端点，**不新写第二套权限判断**。
3. 本版新增的两个二级项在既有组内，`GLOBAL_EXPECTED` 组数不变、叶数变——与 5.9 的组数变更**不是同一处常量**，别改错。

---

## 12. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：7 波 19 个 WBS；编号基座先行；L-60-03 定稿为「重名允许 + 确认，前缀唯一硬拒」 | `PRD.md` v5.10.0 + 评估 01 §3 + 评估 06 §3 |
