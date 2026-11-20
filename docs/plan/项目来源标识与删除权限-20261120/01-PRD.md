# 项目来源标识与差异化删除权限 — PRD

| 项 | 值 |
|---|---|
| 日期 | 2026-11-20 |
| 阶段 | 七道流程第 ③ 环（评估已过，本文待大师批准） |
| 上游 | `00-评估报告.md`；大师 6 点需求 + 向导 4 项裁定 D1~D4 |
| 版本 | 未落号，临时编号 `FR-ORIGIN-xx`，进 release 前统一替换 `FR-vX.Y-xx`；不 bump VERSION |
| 放行 | 未批准；本文写完不实施、不改真库 |

---

## 1. 问题定义（代码级证据，非推断）

| # | 现象 | 证据 | 后果 |
|---|---|---|---|
| P1 | 项目无「自带/自建」来源字段，无法差异化删除 | `Project.java:11-21` 无 origin/builtIn；`ProjectCard.java:22-40` / `projectCards.ts:12-41` 卡片 DTO 也无 | 需求 N3「标识开关」无处落 |
| P2 | `is_demo` 语义是「演示、发版前清零」且全库当前 0 | `V11_65__demo_flag.sql:6-9`；真库 6 项目 `is_demo=0` | 不能复用为自带标识 |
| P3 | 删除有数据项目一律 409，且只清 5 张关系表 | `ProjectService.java:354-359`；`ProjectRepositoryJdbc.java:198-204` | 用户自建临时项目带数据删不掉（大师痛点） |
| P4 | 26 张表 RESTRICT 引用 project，间接子表无 project_id | `v5.17.0/DEV-PLAN.md:221-222`；`purge-temp.sh:164-167` | 粗暴关 busyCounts 会 1451 半删 + 孤儿 |
| P5 | 前端删除按钮置灰只看 hasData，无来源分支，提示单一 | `ProjectCard.vue:95-101,229-239`；`zh-CN.ts:643` 仅 `deleteBlocked` | 自带项目置灰时没有专门悬浮提示（大师 N6） |
| P6 | `canDelete` 只看是否 admin，页面级不按卡片 | `WorkbenchView.vue:121-125,255-267`；`auth.ts:35` | 无法按项目来源控制可删性 |

---

## 2. In/Out Scope

**In Scope（本 PRD 覆盖）**
- 新增 `project.origin`（`BUILTIN` / `USER`）列 + 迁移与存量 marking（N3/N4/N5）
- 后端全链路带出 origin（Project / ProjectCard / 列表接口），前端卡片显示来源徽标（N3/N6）
- ADMIN 在工作台切换项目来源（USER ↔ BUILTIN），带二次确认与审计（N3 + C1）
- 用户自建（USER）项目的**事务性级联彻底删除**：dry-run 清单 → 备份 → 确认 → 按序清理 → 孤儿校验（N2/D1）
- 程序自带（BUILTIN）与 platform 项目维持老规则（有数据 409），按钮置灰 + 专门悬浮提示（N1/N6）
- 机检脚本（含 self-test 真注入）验证 origin 判据、级联顺序、孤儿零残留

**Out Scope（明确不做）**
- 不改 `is_demo` 语义、不碰 `purge-demo.sh` / `purge-temp.sh` 的现有清理逻辑（H8 列开放项）
- 不给 USER 项目做软删除/回收站（D1 已定为级联真删）
- 不改 RF/pytest 内核、不动执行与报告链路
- 不动 `platform()` 单例的不可停用/不可改码/不可删保护（C3 优先级）
- 磁盘产物文件清理（H6 列开放项，本版只清库表）
- 不 bump 版本号、不进 `docs/release/`（待大师落号）

---

## 3. FR 需求（临时编号，每条附验收判据）

### FR-ORIGIN-01 新增 origin 列与迁移

| 项 | 内容 |
|---|---|
| 需求 | `project` 表新增 `origin VARCHAR(16) NOT NULL DEFAULT 'USER'`，取值 `USER`/`BUILTIN`；新增索引；迁移脚本编号取下一个未用号（当前最大 `V11_83`） |
| 落点 | `db/migration/V11_84__project_origin.sql`；`Project.java` 加 `origin` 分量；`ProjectRepositoryJdbc` MAPPER/SELECT 带出 |
| 判据 | ① 迁移在 WSL 手动 `db-migrate.sh` 后 `SHOW COLUMNS FROM project LIKE 'origin'` 一行；② 全量后端单测 PASS（record 加分量不破坏构造）；③ `GET /projects/{id}` 返回 `origin` 字段 |
| 默认 | 新建项目默认 `USER`；种子/模板创建显式 `BUILTIN`（C5） |

### FR-ORIGIN-02 存量项目 marking

| 项 | 内容 |
|---|---|
| 需求 | 按大师裁定 D3：`id=157 PRJ-PYT-202611-003`（网卡测试01）→ `USER`；其余 5 个（id 1/77/80/88/95）→ `BUILTIN` |
| 落点 | 迁移脚本同 FR-01 内 `UPDATE`（幂等 `WHERE origin IS NULL` 或先默认后覆盖）；迁移后只读核对脚本 |
| 判据 | ① `SELECT id,code,origin FROM project` 六行值与 D3 完全一致；② `PLATFORM-LIB` 标 BUILTIN 但 `platform()` 保护不受影响；③ 重复跑迁移不报错（幂等） |

### FR-ORIGIN-03 origin 全链路带出 + 卡片来源徽标

| 项 | 内容 |
|---|---|
| 需求 | `ProjectCard`（后端 record + 前端 interface）加 `origin`；工作台卡片显示「自带/自建」徽标；缺省值 fail-safe 按 `BUILTIN` 处理 |
| 落点 | `domain/iam/ProjectCard.java:22-40`；`frontend/src/api/projectCards.ts:12-41`；`ProjectCard.vue` 模板；i18n 双语键 |
| 判据 | ① `GET /projects/cards` 每条含 `origin`；② 界面 6 张卡片徽标与 DB 一致；③ 故意缺失 origin 的卡片按 BUILTIN 渲染（H9）；④ vitest + vue-tsc PASS |

### FR-ORIGIN-04 来源切换入口（ADMIN 两段式）

| 项 | 内容 |
|---|---|
| 需求 | ADMIN 可在工作台把项目 `USER ↔ BUILTIN` 切换；切换需二次确认（输入项目代码）+ 审计；**切换本身不等于删除**，删除仍需 FR-06 全流程（C1） |
| 落点 | 后端 `PUT /projects/{id}/origin`（PROJECT/UPDATE + ADMIN）；前端 `WorkbenchView` 切换按钮/弹层；`ProjectService.changeOrigin` 写 `audit.recordChange` |
| 判据 | ① 非 ADMIN 调用 403；② 缺项目代码确认 400；③ 切换后 DB 与卡片同步、审计表有 `project.origin.change` 一行（旧值→新值）；④ platform 项目切换被拒 409；⑤ 单测 + 真链路各一条 |

### FR-ORIGIN-05 BUILTIN 置灰 + 专门悬浮提示

| 项 | 内容 |
|---|---|
| 需求 | `origin=BUILTIN` 或 platform 项目：「彻底删除」按钮 `disabled`，`title` 显示专门文案（区别于「有数据只能停用」）；USER 项目按钮按既有 hasData 逻辑 |
| 落点 | `ProjectCard.vue:95-101,229-239`：新增 `deleteLockedByOrigin` computed；`zh-CN.ts:643` / `en-US.ts:636` 后加 `workbench.deleteOriginBlocked` 双语键 |
| 判据 | ① BUILTIN 卡片按钮 disabled 且 title 含「程序自带」类文案（spec 断言）；② USER 空项目按钮可点；③ USER 有数据项目仍按 `deleteBlocked`；④ 双语 key 不显示原始键名；⑤ `ProjectCard.spec.ts` / `WorkbenchView.spec.ts` 相关 describe PASS |

### FR-ORIGIN-06 USER 项目级联彻底删除（核心）

| 项 | 内容 |
|---|---|
| 需求 | `origin=USER` 且非 platform 的项目，ADMIN 输入项目代码确认后，按事务级联清理：间接子表 → 含 project_id 的 24 张 RESTRICT 表 → 5 张关系表 → project；busyCounts 对 USER 不再拦截，BUILTIN/platform 完全不动 |
| 落点 | 新增 `UserProjectPurgeService`（application/iam）+ `DELETE /projects/{id}?purge=true`（或新端点 `/projects/{id}:purge`）；复用 `purge-temp.sh:177-205` 已验证顺序 |
| 判据 | ① 网卡测试01（69 用例+1 集）删除后 `SELECT COUNT(*) FROM project WHERE id=157` = 0，且用例集/用例/间接子表全清；② 删后全库 `project_id` 悬空引用 = 0，6 张间接子表按 case_id/set_id 反查孤儿 = 0；③ 出现 RESTRICT 冲突时事务回滚，项目与其数据完整保留（无半删）；④ BUILTIN 有数据项目调用仍 409；⑤ platform 调用 409；⑥ 非 ADMIN 403 |

### FR-ORIGIN-07 dry-run 清单 + 备份 + 审计

| 项 | 内容 |
|---|---|
| 需求 | 级联删除前先出 dry-run（逐表条数与对象清单，仿 `purge-temp.sh --dry-run`），ADMIN 确认后自动备份（库 dump），再执行；删除写审计 `project:purge` |
| 落点 | 新增 `POST /projects/{id}/purge-preview`（只读）；`UserProjectPurgeService` 内备份钩子 + 审计；前端确认弹层展示清单摘要 |
| 判据 | ① dry-run 不写任何库（前后计数一致）；② 清单含逐表条数且与实际删除数一致；③ 备份文件存在且可 `sha256` 校验（仿 `scripts/ops/backup.sh` 惯例，注意 .sha256 不含文件名、要比对 hash 值）；④ 审计记录含 code/name/操作人 |

### FR-ORIGIN-08 删除后前端上下文刷新

| 项 | 内容 |
|---|---|
| 需求 | 若删除的是当前选中项目，删除成功后自动切到列表第一个项目，避免上下文指向已删项目 |
| 落点 | `WorkbenchView.vue:171-185 confirmDelete` + `:130-138 refreshProjects` |
| 判据 | ① 删当前项目后 `auth.projectId` 指向现存项目；② 顶栏下拉与卡片列表同步刷新；③ vitest `WorkbenchView.spec.ts` 删除弹层 describe PASS |

### FR-ORIGIN-09 机检 + 门禁 + 真链路

| 项 | 内容 |
|---|---|
| 需求 | 新增机检脚本验证 origin 判据 / 删除顺序 / 孤儿零残留，含 `--self-test` 真注入判红；跑全量门禁 FAILS=0；真链路验证 FR-02/04/05/06/07 |
| 落点 | `scripts/guard/project_origin_guard.sh`（E 盘+WSL 同源）；真链路 python 脚本 |
| 判据 | ① 机检正常态 FAILS=0，self-test 注入后判红；② `bash scripts/gates.sh` 全量 FAILS=0 UNJUDGED=0；③ 真链路：6 项目 origin 值、BUILTIN 删除 409、USER 级联删除后孤儿=0 全部 PASS |

### FR 覆盖核对

| FR | 主交付 | 对应大师需求 |
|---|---|---|
| FR-ORIGIN-01 | 迁移与 record | N3 |
| FR-ORIGIN-02 | marking | N4/N5 |
| FR-ORIGIN-03 | 全链路+徽标 | N3/N6 |
| FR-ORIGIN-04 | 切换入口 | N3 |
| FR-ORIGIN-05 | 置灰+提示 | N6/N1 |
| FR-ORIGIN-06 | 级联删除 | N2 |
| FR-ORIGIN-07 | dry-run+备份+审计 | N2（安全） |
| FR-ORIGIN-08 | 上下文刷新 | 周边 |
| FR-ORIGIN-09 | 机检+门禁+真链路 | 全 |

> **禁补号**：新增 FR 一律取下一个未用序号，不插号重排（机检按连续号判）。

---

## 4. 口径汇总（已裁定，实施不得自行改动）

| 项 | 口径 | 来源 |
|---|---|---|
| 来源字段 | `project.origin` = `USER` / `BUILTIN`，默认 `USER` | D2 + C5 |
| 删除语义 | USER = 级联真删；BUILTIN/platform = 老规则有数据 409 | D1 + N1 |
| platform 优先级 | `platform()` 硬保护早于 origin 分支，origin 不给 platform 开后门 | C3 |
| 权限放大 | 切换 origin 与删除是两段式，各自确认+审计 | C1 |
| marking | 网卡测试01=USER，其余 5 个=BUILTIN | D3 |
| 置灰提示 | BUILTIN 有专门 title，区分「有数据」与「自带不可删」 | N6/C4 |
| is_demo | 不动、不复用；双轨关系留开放项 | D2/§2.4 |
| 版本 | 临时 `FR-ORIGIN`，不 bump，不进 release | D4 |

---

## 5. NFR（逐条可判定）

| ID | 需求 | 判据 |
|---|---|---|
| NFR-1 | 级联删除事务性 | 任一 RESTRICT 冲突 → 全部回滚，`SELECT` 验证项目与其子表数据完整 |
| NFR-2 | 孤儿零残留 | 删后全库 `project_id` 悬空=0，6 张间接子表反查孤儿=0 |
| NFR-3 | 权限 fail-closed | 非 ADMIN 的切换/删除/PURGE 全部 403，且无信息泄露（不返回项目存在性差异） |
| NFR-4 | 幂等 | 迁移重复跑不报错；重复删除同一项目返回 404 不 500 |
| NFR-5 | 审计完整 | 切换与删除各写一行审计，含操作人/时间/旧值新值 |
| NFR-6 | 双语 | 新增 i18n 键 zh/en 同步，界面无原始键名 |
| NFR-7 | 双端同源 | E 盘文档工作区与 WSL 权威副本（ext4）改动文件 LF 规范化后 sha 一致 |
| NFR-8 | 不误伤现有链路 | 全量后端单测 + 前端 vitest/build + 68 步门禁 FAILS=0 |

---

## 6. 门禁与放行判据

| 门禁 | 命令 | 通过标准 |
|---|---|---|
| 表格机检 | `python docs/evidence/v1.0/policy-20261106/check_md_tables.py <四文档>` | PASS |
| 契约 | `python3 tools/gen_api_contract.py`（新增端点后重生成）+ `--check` | PASS |
| 内核解耦 | `python3 tools/check_kernel_decoupling.py` | PASS |
| px/token | `python3 tools/check_tokens.py` | PASS |
| 后端单测 | `mvn -q test`（WSL） | 全量 PASS，零失败 |
| 前端 | `vue-tsc` + `vitest run` + `npm run build` | 全 PASS |
| 机检 | `bash scripts/guard/project_origin_guard.sh` + `--self-test` | 正常 FAILS=0；self-test 判红 |
| 真链路 | python 脚本打 API | FR-02/04/05/06/07 全 PASS |
| 全量门禁 | `bash scripts/gates.sh` | FAILS=0 UNJUDGED=0 |

**放行硬条件**：D1 级联真删必须通过 NFR-1/NFR-2（事务+零孤儿），否则不放行——这是 v5.17 半删事故的硬教训，不是软目标。

---

## 7. 风险与开放项

| ID | 风险/开放项 | 等级 | 处置 |
|---|---|---|---|
| R1 | 切换 origin 放大权限误删自带项目 | 高 | 两段式确认 + 审计 + 备份；platform 优先保护 |
| R2 | 级联顺序错 → 1451 半删/孤儿 | 高 | 复用已验证顺序 + 事务 + 删后扫描 + self-test |
| R3 | 真库迁移在双仓不同步 | 中 | 只在 WSL 跑 `db-migrate.sh`；E 盘只同步文件 |
| R4 | 两个 demo 标 BUILTIN 后「双无出路」 | 中 | 已请大师阅知；本版不改 purge 脚本 |
| R5 | 磁盘产物文件（报告 PDF/附件）是否随删 | 中 | 开放项 O-2：本版只清库表，磁盘文件保留；若需清另案 |
| O-1 | `is_demo` 与 origin 双轨：未来 `purge-demo.sh` 是否改认 origin | 中 | 留评审；本版不动 |
| O-2 | 删除是否同步清理磁盘产物（`report_artifact` 等） | 中 | 留大师裁定，默认不删 |
| O-3 | 是否给「已删除项目」留 30 天可恢复窗口 | 低 | 本版不做（D1=真删），备份即恢复手段 |
| O-4 | 正式版本号与 FR 编号落号 | 低 | 大师批准实施后拍板，统一替换 |

---

## 8. 状态与下一步

- **当前**：评估（第 ② 环）+ PRD（第 ③ 环）成件，**待大师批准**。
- **批准后才进入**：DEV-PLAN（第 ④ 环）→ TEST-PLAN（第 ⑤ 环）→ 开发/真链路/门禁（第 ⑥ 环）→ 文档回写（第 ⑦ 环）。
- **本轮零变更**：未写代码、未跑迁移、未改真库、未 bump 版本、未提交。
- **不称 GA**：G 门未判、版本未落号。
