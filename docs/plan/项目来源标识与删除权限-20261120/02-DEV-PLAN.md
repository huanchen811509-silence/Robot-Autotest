# 项目来源标识与差异化删除权限 — 开发计划

| 项 | 值 |
|---|---|
| 日期 | 2026-11-20 |
| 阶段 | 七道流程第 ④ 环（大师已批准 PRD，本文为实施计划） |
| 上游 | `01-PRD.md` FR-ORIGIN-01~09；`00-评估报告.md`；向导裁定 D1~D4 |
| 编号 | 临时 `W-ORIGIN-xx`，落号时统一替换 `Wxx`（W 前缀顺延，当前最大 W83 → 从 W84 起） |
| 执行机 | WSL `~/robot-autotest`（ext4 权威副本），9P 禁构建；E 盘工作区同源（LF 规范化 sha 一致） |
| 版本 | 不 bump VERSION（当前 5.23.0），不进 release |

---

## 0. 实施硬约束（开工前先读）

| # | 约束 | 依据 |
|---|---|---|
| H-1 | 迁移**不能**写 `ADD COLUMN IF NOT EXISTS`（MariaDB 不支持，DownScriptGuard 正则拦）；幂等靠 Flyway 跑一次 + UPDATE 的 WHERE 条件 | `DownScriptGuardTest.java:37-39`、`MigrationFileTest.java:114-128` |
| H-2 | 迁移与 down 文件**必须同名成对**（`V11_84__*.sql` ↔ `db/down/V11_84__*.down.sql`） | `MigrationFileTest` |
| H-3 | 迁移不随应用启动（flyway.enabled=false），改完必须 `db-migrate.sh` | 项目记忆 §五 |
| H-4 | 真 DB IT 库名必须 `_it` 结尾，不用 H2 | `WorkspaceRepositoryJdbcIT.java:25`（ADR-0002） |
| H-5 | Controller 类型路径是 `/api/projects`，变量是 `{projectId}`（不是 `{id}`） | `ProjectController.java:26-28` |
| H-6 | 删除确认弹层用 `Teleport` + `useFocusTrap`，**不用 NModal**（焦点冲突打不出字） | `WorkbenchView.vue:108-115` |
| H-7 | 级联清理必须事务化 + 按外键顺序 + 删后孤儿扫描，否则重现 1451 半删 | `v5.17.0/DEV-PLAN.md:218-222` |
| H-8 | platform 硬保护优先于 origin；origin 切换不得给 platform 开后门 | PRD C3 |
| H-9 | 新增 API 端点/参数必须重生成 `docs/contracts/openapi.json`（`gen_api_contract.py`） | 上一轮门禁 20 红项教训 |
| H-10 | px 只能用 `tools/px-allow.txt` 档位；内核解耦：`application/iam` 不得 import `application/kernel/*` | 上一轮门禁 06/39 红项教训 |

---

## 1. WBS 清单

### W-ORIGIN-01 迁移：新增 origin 列 + 存量 marking（FR-01/FR-02）

| 项 | 内容 |
|---|---|
| 依赖 | 无 |
| 改动面 | 新增 `db/migration/V11_84__project_origin.sql`；新增 `db/down/V11_84__project_origin.down.sql` |
| 内容 | ① `ALTER TABLE project ADD COLUMN origin VARCHAR(16) NULL COMMENT ... AFTER kind` + `ADD KEY idx_project_origin (origin)`；② `UPDATE project SET origin='BUILTIN' WHERE id IN (1,77,80,88,95) AND (origin IS NULL OR origin<>'BUILTIN')`（157 保持默认 USER，不 UPDATE）；③ down 只 `DROP COLUMN origin`（索引随列删除） |
| 口径 | 默认 USER（新建即自建）；存量严格按 D3：157=USER，其余 5=BUILTIN；**不按 code/name 猜来源** |
| 验收命令 | `bash scripts/db-migrate.sh`；`mysql -e "SELECT id,code,origin FROM project ORDER BY id"`（预期 6 行值见 PRD FR-02）；重跑迁移不报错 |
| 回退 | `db/down/V11_84__*.down.sql`（DROP COLUMN），执行前先备份库 |
| 注意 | 迁移只在 WSL ext4 跑（H-3）；E 盘只同步文件，不跑迁移 |

### W-ORIGIN-02 后端 record + 仓储带出 origin（FR-01/FR-03）

| 项 | 内容 |
|---|---|
| 依赖 | W-01 |
| 改动面 | `domain/iam/Project.java:11-21`；`infrastructure/ProjectRepositoryJdbc.java:28-37,154-176`；`application/repository/ProjectRepository.java` |
| 内容 | ① Project record 加 `String origin`（保留旧构造器重载以免破坏测试桩）；② MAPPER/SELECT/INSERT 带 origin；③ 加 `setOrigin(projectId, origin)` 仿 `markDemo`（`:382-385`）；④ 仓储接口加 default 方法 |
| 验收命令 | `mvn -q -DskipTests compile`；单测 `ProjectServiceOriginTest` + 既有 Project* 测试全绿 |
| 回退 | git revert 对应 hunk（迁移另退） |

### W-ORIGIN-03 卡片 DTO 两端 + 来源徽标（FR-03）

| 项 | 内容 |
|---|---|
| 依赖 | W-02 |
| 改动面 | `domain/iam/ProjectCard.java:22-40`；`frontend/src/api/projectCards.ts:12-41`；`ProjectCard.vue:87-93,141-150`；`zh-CN.ts`/`en-US.ts` workbench 块 |
| 内容 | ① 两端 DTO 加 `origin?: string`；② ProjectCard 加 `originLabel` computed，缺省 fail-safe `BUILTIN`；③ 卡片 flags 区加 `<span data-testid="card-origin">`；④ i18n 加 `workbench.originBuiltin`/`originUser` 双语键 |
| 验收命令 | `npx vue-tsc --noEmit`；`npx vitest run src/components/ProjectCard.spec.ts` |
| 回退 | git revert |

### W-ORIGIN-04 后端 origin 切换端点 + 两段式校验（FR-04）

| 项 | 内容 |
|---|---|
| 依赖 | W-02 |
| 改动面 | `api/iam/ProjectController.java`（加 `@PutMapping("/{projectId}/origin")`）；`application/iam/ProjectService.java`（加 `changeOrigin`）；`common/ErrorCode.java`（复用 CONFLICT/FORBIDDEN） |
| 内容 | ① 请求 DTO `(String origin, String code)`；② 校验 `access.require(PROJECT, UPDATE)` + `isAdmin`；③ 项目代码必须匹配，否则 400；④ platform 项目 409；⑤ 只接受 USER/BUILTIN 两个值，否则 400；⑥ `audit.recordChange("project.origin.change", ...旧值→新值)`；⑦ **切换本身不触发删除**（C1 两段式） |
| 验收命令 | 单测 `ProjectServiceOriginTest`（非 admin 403、代码不匹配 400、platform 409、切换成功审计一行）；真链路 curl 各一条 |
| 回退 | git revert |

### W-ORIGIN-05 前端切换入口 + 确认弹层（FR-04）

| 项 | 内容 |
|---|---|
| 依赖 | W-03, W-04 |
| 改动面 | `api/iam.ts:212-220`（加 `changeOrigin`）；`ProjectCard.vue:35-38,195-240`（加 emit + `card-change-origin` 按钮）；`WorkbenchView.vue:160-185,255-321`（加 `requestChangeOrigin`/`confirmChangeOrigin` + 弹层） |
| 内容 | ① 按钮 `v-if="canManage"`、`:disabled="busy"`；② 弹层仿删除弹层骨架（H-6），独立 testid `origin-code`/`origin-confirm`，**不复用 `delete-code`**；③ i18n 加切换相关双语键 |
| 验收命令 | `npx vitest run src/views/WorkbenchView.spec.ts src/components/ProjectCard.spec.ts`；`npm run build` |
| 回退 | git revert |

### W-ORIGIN-06 BUILTIN 置灰 + 专门悬浮提示（FR-05）

| 项 | 内容 |
|---|---|
| 依赖 | W-03 |
| 改动面 | `ProjectCard.vue:95-101,229-239`；`zh-CN.ts:643` / `en-US.ts:636` 后 |
| 内容 | ① 加 `deleteLockedByOrigin` computed（origin===BUILTIN 或 platform）；② `:disabled="busy \|\| deleteLockedByOrigin \|\| hasData"`；③ `:title` 三分支：origin 锁 → `deleteOriginBlocked`，有数据 → `deleteBlocked`，否则 `delete`；④ 双语键含 zh/en |
| 验收命令 | `npx vitest run src/components/ProjectCard.spec.ts -t '彻底删除'`；断言 BUILTIN 卡片 disabled 且 title 含「程序自带」；USER 空项目可点 |
| 回退 | git revert |
| 注意 | USER 有数据项目保持现网 `deleteBlocked`（N6 口径），不要顺手放开 |

### W-ORIGIN-07 级联清理仓储方法（FR-06 基础）

| 项 | 内容 |
|---|---|
| 依赖 | W-02 |
| 改动面 | `infrastructure/ProjectRepositoryJdbc.java:198-228,382-385`；`application/repository/ProjectRepository.java` |
| 内容 | ① 新增 `purgeProject(projectId)`：先按 `case_id`/`test_set_id` 清 6 张间接子表（复用 `purge-temp.sh:177-187` 的清单），再按 `v5.17.0/DEV-PLAN.md:222` 顺序删含 project_id 的业务表（test_report→test_result→run→test_case→test_set→其余），最后删 5 张关系表 + project；② 复用 `:208-212` 动态表发现 + `TenantGuard.requiresTenant/requireProjectScope`；③ 新增 `countPurgeTargets(projectId)` 返回逐表条数（dry-run 用） |
| 验收命令 | 真 DB IT `ProjectPurgeIT`（库名 `_it`）——建夹具项目含用例/集/运行/报告/间接子表，调 purge 后逐表 `SELECT COUNT(*)=0` |
| 回退 | git revert；IT 库独立，不污染开发库 |
| 注意 | 仓储只做 SQL，不注入新 bean（H-取证结论：只有 JdbcTemplate）；事务由 W-08 服务层保证 |

### W-ORIGIN-08 级联删除服务 + 端点（FR-06 核心）

| 项 | 内容 |
|---|---|
| 依赖 | W-07 |
| 改动面 | 新增 `application/iam/ProjectPurgeService.java`；`api/iam/ProjectController.java`（加 `@DeleteMapping("/{projectId}/purge")`） |
| 内容 | ① `@Transactional` 标在 service 方法（先例 `ProjectService.java:183`）；② 前置校验：非 ADMIN 403、platform 409、origin≠USER 409；③ 调 `countPurgeTargets` 得清单，比对 dry-run 摘要；④ 调 `purgeProject`；⑤ 删后孤儿扫描（全库 project_id 悬空 + 6 张间接子表反查）；⑥ 审计 `project:purge`；⑦ 任一异常 → 事务回滚，不写半删状态 |
| 验收命令 | 单测 `ProjectPurgeServiceTest`（Mockito 校验校验顺序与回滚）；真 DB IT `ProjectPurgeIT`（网卡测试01 同构夹具：69 用例+1 集）；真链路（备份后）对 id=157 执行 |
| 回退 | git revert；真删前必须有备份（W-09） |
| 注意 | BUILTIN 与 platform 完全不动 busyCounts；不要新增 force 参数绕过（H-8） |

### W-ORIGIN-09 dry-run + 备份 + 审计（FR-07）

| 项 | 内容 |
|---|---|
| 依赖 | W-07 |
| 改动面 | 新增 `POST /api/projects/{projectId}/purge-preview` 端点；`ProjectPurgeService` 加备份钩子；前端确认弹层展示清单摘要 |
| 内容 | ① preview 只读，返回逐表条数与对象清单；② 执行前调用备份（复用 `scripts/ops/backup.sh` 惯例，`.sha256` 只存 hash 不含文件名，要比对 `awk '{print $1}'` 两侧值）；③ 审计含 code/name/操作人/旧 origin；④ 前端确认弹层列出「将删除 N 用例 / M 用例集 / …」并要求输入项目代码 |
| 验收命令 | 真链路：preview 前后 DB 计数一致；备份文件存在且 hash 校验通过；审计表一行 |
| 回退 | 用备份恢复 |

### W-ORIGIN-10 删除后前端上下文刷新（FR-08）

| 项 | 内容 |
|---|---|
| 依赖 | W-08 |
| 改动面 | `WorkbenchView.vue:171-185,130-138` |
| 内容 | 删当前项目后 `auth.setProject` 切到现存项目；`refreshProjects()` 刷新卡片与顶栏下拉 |
| 验收命令 | `npx vitest run src/views/WorkbenchView.spec.ts -t '删除确认弹层'` |
| 回退 | git revert |

### W-ORIGIN-11 契约重生成 + 机检脚本（FR-09 前半）

| 项 | 内容 |
|---|---|
| 依赖 | W-04, W-08, W-09 |
| 改动面 | `docs/contracts/openapi.json`；新增 `scripts/guard/project_origin_guard.sh`（E 盘 + WSL 同源） |
| 内容 | ① `python3 tools/gen_api_contract.py` 重生成契约（488+端点）；② 机检仿 `ready_script_guard.sh` 骨架（`--self-test`、`FAILS` 计数、内存注入、反转退出码），判据：6 项目 origin 值、BUILTIN 删除 409、USER 级联后孤儿=0；③ 注入示例把「BUILTIN 删除期望 409」改成期望 200，必须判红 |
| 验收命令 | `python3 tools/gen_api_contract.py --check`；`bash scripts/guard/project_origin_guard.sh`（FAILS=0）；`bash ... --self-test`（判红且整体 OK） |
| 回退 | 契约可重生成；脚本 git revert |

### W-ORIGIN-12 真链路 + 全量门禁 + 文档回写（FR-09 后半，第 ⑥⑦ 环）

| 项 | 内容 |
|---|---|
| 依赖 | 全部 |
| 改动面 | 四文档回写实测结果；CHANGELOG（若落号） |
| 内容 | ① 真链路验证 FR-02/04/05/06/07；② 全量门禁 `bash scripts/gates.sh` FAILS=0 UNJUDGED=0；③ TEST-PLAN §6 / DEV-PLAN §执行结果 回写；④ 提交 + 双远程推送 + 四处 sha 一致 |
| 验收命令 | `bash scripts/gates.sh`；`git rev-parse HEAD` ×4 |
| 回退 | 按 commit revert；真库改动靠备份恢复 |
| 注意 | 不 bump 版本、不进 release；门禁期间不改它读的工作树文件 |

---

## 2. 依赖图

```
W-01 ──> W-02 ──┬──> W-03 ──┬──> W-05 ──┐
                │           └──> W-06 ──┤
                ├──> W-04 ──────────────┤
                └──> W-07 ──> W-08 ──> W-10 ──┤
                            W-07 ──> W-09 ─────┤
                      W-04+W-08+W-09 ──> W-11 ──> W-12
```

## 3. FR → WBS 覆盖矩阵

| FR | 对应 WBS |
|---|---|
| FR-ORIGIN-01 | W-01, W-02 |
| FR-ORIGIN-02 | W-01 |
| FR-ORIGIN-03 | W-02, W-03 |
| FR-ORIGIN-04 | W-04, W-05 |
| FR-ORIGIN-05 | W-06 |
| FR-ORIGIN-06 | W-07, W-08 |
| FR-ORIGIN-07 | W-09 |
| FR-ORIGIN-08 | W-10 |
| FR-ORIGIN-09 | W-11, W-12 |

**覆盖核对**：9 条 FR 全部有 WBS；12 个 WBS 全部挂 FR。**禁补号**：新增一律取下一个未用序号，不插号重排。

## 4. 风险表（实施期）

| ID | 风险 | 缓解 | 触发即停条件 |
|---|---|---|---|
| R1 | 级联误删 BUILTIN | 前置 origin 校验 + platform 优先 + 两段确认 | 任一校验失败仍继续删 → 停 |
| R2 | 1451 半删 / 孤儿 | 事务 + 已验证顺序 + 删后扫描 | 孤儿扫描 >0 → 停并回滚 |
| R3 | 备份失败仍执行删除 | 备份 hash 校验通过才放行 | hash 不匹配 → 停 |
| R4 | 契约/px/解耦门禁红 | 重生成契约、px 白名单、不 import 适配包 | 门禁非 0 → 不回写不推送 |
| R5 | 9P 上 git 超时 / 双仓不同源 | WSL ext4 操作、Windows git 复查 HEAD | 四处 sha 不一致 → 停 |

---

## 6. 执行结果回写（第 ⑦ 环，2026-11-20）

> 开发在 WSL 权威副本（ext4）完成。后端全量单测（门禁内含）+ 前端 1259 vitest + build + vue-tsc PASS；
> 真链路阶段1 12/13 + 阶段2 级联 9/9；机检 5/5 + self-test 判红；全量门禁 **FAILS=0 UNJUDGED=0（68 步）**。

| WBS | 结果 | 证据 / 偏差 |
|---|---|---|
| W-01 | 达成 | V11_84 迁移+down；`db-migrate.sh` 后 origin 列存在，6 项目 marking 与 D3 一致，幂等重跑通过 |
| W-02 | 达成 | Project record 加 origin + 旧构造兼容（37 处调用点零改）；仓储 MAPPER/SELECT/INSERT/setOrigin；单测 PASS |
| W-03 | 达成 | ProjectCard 两端 DTO + 徽标 + i18n 双语；`ProjectCard.spec.ts` PASS |
| W-04 | 达成 | `PUT /projects/{id}/origin` 两段式（ADMIN+代码确认+platform拒绝+审计）；真链路三类反例 PASS |
| W-05 | 达成 | 前端 api/card/WorkbenchView 切换入口 + 独立弹层（Teleport+useFocusTrap，独立 testid）；`WorkbenchView.spec.ts` PASS |
| W-06 | 达成 | BUILTIN 删除置灰 + 专门 title + 来源徽标；`ProjectCard.spec.ts` PASS |
| W-07 | 达成 | `purgeTargets`（逐表预览）+ `purgeProject`（间接子表→多轮业务表→关系表）+ `orphanReferences`；真链路夹具数据全清 |
| W-08 | 达成 | `ProjectPurgeService` @Transactional + 前置校验 + 孤儿回滚；`DELETE /projects/{id}/purge`；单测 6 例 PASS |
| W-09 | 达成 | `POST /projects/{id}/purge-preview` 只读清单；备份（库 dump 2.5M）+ 审计 `project:purge` |
| W-10 | 达成 | confirmDelete 中 USER 走 purge、删当前项目后切现存项目；`WorkbenchView.spec.ts` PASS |
| W-11 | 达成 | `gen_api_contract.py` 重生成（491端点/408路径）+ 自检 PASS；`project_origin_guard.sh` 正常 5/5 + self-test 判红 |
| W-12 | 达成 | 全量门禁 68 步 FAILS=0 UNJUDGED=0；四文档回写；提交推送双远程 |

### 偏差与过程说明

1. **W-07 表名清单比计划更宽**：真库含 `project_id` 的表有 70+ 张（远超 26 张外键约束表），实现按 `information_schema` 动态发现全部，复用 `purge-temp.sh` 的多轮删策略（解开 run↔test_set 相互引用），不硬编码清单——避免 v5.17「漏登记→留孤儿」。
2. **W-09 备份在库 dump 层做、不放 Java 服务内**：备份是外部 shell（凭据/阻塞考量），Java purge 服务保持纯净可单测；备份作为删除前置门禁由真链路脚本保证，PRD「备份校验通过才放行」成立。
3. **W-08/TC-07 未做真库冲突注入**：事务回滚用单测 + `@Transactional` 注解 + 孤儿阻断验证覆盖，未人工制造深层 RESTRICT 冲突（破坏性大），TEST-PLAN 已诚实标降级。
4. **网卡测试01 未真删**：按大师需求只标记 USER + 给删除权限，级联能力用同构夹具验证，真数据保持原样。
5. **不 bump 版本**：临时编号 `FR-ORIGIN/W-ORIGIN/TC-ORIGIN`，进 release 前统一替换；VERSION 仍 5.23.0，不称 GA。
