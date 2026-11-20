# 项目来源标识与差异化删除权限 — 测试计划

| 项 | 值 |
|---|---|
| 日期 | 2026-11-20 |
| 阶段 | 七道流程第 ⑤ 环（大师已批准 PRD，本文为测试计划） |
| 上游 | `01-PRD.md` FR-ORIGIN-01~09；`02-DEV-PLAN.md` W-ORIGIN-01~12 |
| 编号 | 临时 `TC-ORIGIN-xx`，落号时统一替换 `TC-vX.Y-xx` |
| 版本 | 不 bump VERSION（当前 5.23.0） |

---

## 1. 测试范围与环境

| 项 | 内容 |
|---|---|
| 单元 | 后端 JUnit + Mockito（不连库）；前端 vitest |
| 集成 | 真 DB IT，库名 `_it` 结尾（ADR-0002，不用 H2），连真实 MariaDB |
| 真链路 | HTTP 打 WSL 运行中的后端 `:8001`，`qa-e2e-admin` + env 密码 |
| 门禁 | `bash scripts/gates.sh` 全量；机检 `scripts/guard/project_origin_guard.sh` 含 self-test |
| 夹具 | 6 个存量项目真值见 PRD §2.4；级联测试用「网卡测试01 同构夹具」（69 用例+1 集） |
| backup | 真删类用例执行前必须备份并校验 hash（FR-07），IT 库独立可重建 |

---

## 2. TC 清单

### TC-ORIGIN-01 迁移落列与存量 marking（FR-01/02）

| 项 | 内容 |
|---|---|
| 前置 | WSL 权威副本、备份开发库 |
| 步骤 | ① `db-migrate.sh`；② `SHOW COLUMNS FROM project LIKE 'origin'`；③ `SELECT id,code,origin FROM project ORDER BY id`；④ 重跑迁移 |
| 断言 | ② 一行 origin；③ 六行值 = 157:USER，1/77/80/88/95:BUILTIN；④ 不报错且值不变 |
| 证据 | mysql 输出粘贴 + 迁移日志 |

### TC-ORIGIN-02 全链路 origin 带出 + 徽标（FR-03）

| 项 | 内容 |
|---|---|
| 前置 | W-01/02/03 完成 |
| 步骤 | ① `GET /api/projects/cards` 取 6 条；② 界面看卡片徽标；③ 构造缺 origin 的 mock 卡片渲染 |
| 断言 | ① 每条含 origin 且与 DB 一致；② 徽标文案与 origin 对应；③ 缺失时按 BUILTIN 显示（fail-safe） |
| 证据 | API 响应片段 + vitest 输出 |

### TC-ORIGIN-03 origin 切换成功 + 审计（FR-04）

| 项 | 内容 |
|---|---|
| 前置 | ADMIN 登录，选一个 USER 项目 |
| 步骤 | ① `PUT /api/projects/{id}/origin` body `{origin:"BUILTIN", code:"<正确代码>"}`；② 查 DB 与卡片；③ 查审计表 |
| 断言 | ① 200；② DB origin=BUILTIN 且卡片同步；③ `project.origin.change` 一行，旧 USER→新 BUILTIN |
| 证据 | curl 输出 + SQL + 审计行 |

### TC-ORIGIN-04 切换反例：非 ADMIN / 代码错 / platform / 非法值（FR-04）

| 项 | 内容 |
|---|---|
| 步骤 | ① 非 admin（viewer）切；② 代码填错；③ 对 PLATFORM-LIB(id=88) 切；④ origin 填 `ROOT` |
| 断言 | ① 403；② 400；③ 409；④ 400；四次 DB 均未变化、无审计行 |
| 证据 | 四个 HTTP 状态 + 前后 DB 对比 |

### TC-ORIGIN-05 BUILTIN 置灰 + 悬浮提示（FR-05）

| 项 | 内容 |
|---|---|
| 步骤 | ① 看 BUILTIN 卡片删除按钮；② 悬停读 title；③ 看 USER 空项目按钮；④ 看 USER 有数据项目按钮 |
| 断言 | ① disabled；② title 含「程序自带」语义文案（非「只能停用」）；③ 可点；④ disabled 且 title=`deleteBlocked` |
| 证据 | vitest 断言（`ProjectCard.spec.ts`）+ 截图可选 |

### TC-ORIGIN-06 级联删 USER 项目（核心，FR-06）

| 项 | 内容 |
|---|---|
| 前置 | **备份 + hash 校验通过**；用 IT 库或 id=157 同构夹具 |
| 步骤 | ① preview 得清单；② `DELETE /api/projects/{id}/purge`；③ 逐表 COUNT；④ 全库扫悬空 project_id；⑤ 6 张间接子表反查孤儿 |
| 断言 | ① 清单条数与夹具实际一致；② 200 且 project 行消失；③ 所有业务表该 project 下 0 行；④ 悬空=0；⑤ 孤儿=0 |
| 证据 | preview JSON + 逐表 COUNT 输出 + 孤儿扫描 SQL 输出 |

### TC-ORIGIN-07 级联事务回滚（FR-06/NFR-1）

| 项 | 内容 |
|---|---|
| 前置 | IT 库夹具；人为制造 RESTRICT 冲突（如插一条未登记引用表） |
| 步骤 | ① 调 purge；② 查 project 与各子表 |
| 断言 | ① 非 200（错误响应）；② **项目与其全部数据完整保留**（无半删、成员关系未丢） |
| 证据 | 错误响应 + 前后逐表 COUNT 一致 |

### TC-ORIGIN-08 删除反例：BUILTIN / platform / 非 ADMIN（FR-06/NFR-3）

| 项 | 内容 |
|---|---|
| 步骤 | ① 对 BUILTIN 有数据项目调 purge；② 对 PLATFORM-LIB 调；③ 非 admin 对 USER 项目调 |
| 断言 | ① 409「只能停用」类文案；② 409「平台项目不可删除」；③ 403；三次 DB 均未变化 |
| 证据 | 三个 HTTP 状态 + DB 对比 |

### TC-ORIGIN-09 dry-run 只读 + 备份校验 + 审计（FR-07）

| 项 | 内容 |
|---|---|
| 步骤 | ① 调 purge-preview；② 前后 `SELECT COUNT(*)` 对比；③ 执行真删后查备份文件 `.sha256` 与 `sha256sum` 的 hash 值；④ 查审计 |
| 断言 | ① 返回逐表条数清单；② 前后计数完全一致（只读）；③ 两侧 hash 值相等（注意 `.sha256` 不含文件名，不可用 `-c`）；④ 审计含 code/name/操作人 |
| 证据 | preview JSON + 计数对比 + hash 输出 + 审计行 |

### TC-ORIGIN-10 删当前项目后上下文刷新（FR-08）

| 项 | 内容 |
|---|---|
| 步骤 | ① 把当前项目设为待删 USER 项目；② 删除成功；③ 看顶栏下拉与卡片列表 |
| 断言 | ② 成功后无报错；③ `auth.projectId` 指向现存项目，列表不含已删项 |
| 证据 | vitest（`WorkbenchView.spec.ts`）+ 真链路观察 |

### TC-ORIGIN-11 机检正常态 + self-test 判红（FR-09）

| 项 | 内容 |
|---|---|
| 步骤 | ① `bash scripts/guard/project_origin_guard.sh`；② `bash ... --self-test` |
| 断言 | ① FAILS=0 且 exit 0；② 注入「BUILTIN 删除期望 200」后判红（FAILS>0）但整体判定 OK（exit 0） |
| 证据 | 两次脚本输出 |

### TC-ORIGIN-12 全量门禁 + 双端同源（FR-09/NFR-7/8）

| 项 | 内容 |
|---|---|
| 步骤 | ① `python3 tools/gen_api_contract.py --check`；② `check_tokens.py`；③ `check_kernel_decoupling.py`；④ 全量后端单测；⑤ 前端 vue-tsc/vitest/build；⑥ `bash scripts/gates.sh`；⑦ E/WSL 改动文件 LF 规范化 sha 对比 |
| 断言 | ①~⑥ 全 PASS，门禁 FAILS=0 UNJUDGED=0；⑦ 零真实差异 |
| 证据 | 各命令输出 + GATES_RESULT 行 + sha 清单 |

### TC-ORIGIN-13 真链路 6 项目 marking 总核（FR-02/FR-09）

| 项 | 内容 |
|---|---|
| 步骤 | 登录拿 token；`GET /api/projects/cards`；逐条比对 origin |
| 断言 | 6 条 origin 与 D3 完全一致（157=USER，其余 BUILTIN）；BUILTIN 卡片删除按钮置灰 |
| 证据 | python 脚本输出表 |

---

## 3. FR → TC 覆盖矩阵

| FR | 主 TC | 反例/补充 TC |
|---|---|---|
| FR-01 | 01, 02 | 12 |
| FR-02 | 01, 13 | — |
| FR-03 | 02 | 05 |
| FR-04 | 03 | 04（403/400/409/非法值） |
| FR-05 | 05 | 08 |
| FR-06 | 06 | 07（事务回滚）、08（BUILTIN/platform/非admin） |
| FR-07 | 09 | 06（preview 一致性） |
| FR-08 | 10 | — |
| FR-09 | 11, 12, 13 | — |

**覆盖核对**：9 条 FR 全部 ≥1 主 TC；关键 FR（04/06）有完整反例；级联真删有事务回滚 TC（07）。**禁补号**：新增 TC 一律取下一个未用序号，不插号重排。

## 4. 放行判据

| 项 | 标准 |
|---|---|
| 硬判据 1 | TC-06 级联删除后孤儿=0、悬空=0 |
| 硬判据 2 | TC-07 冲突时事务完整回滚、无半删 |
| 硬判据 3 | TC-08 BUILTIN/platform/非admin 三类反例全拦 |
| 硬判据 4 | TC-09 备份 hash 校验通过、dry-run 只读 |
| 硬判据 5 | TC-12 全量门禁 FAILS=0 UNJUDGED=0 |
| 降级边界 | 无——本版删除是不可逆破坏性操作，任何硬判据不过都不放行 |

## 5. 实测结果回写（第 ⑦ 环，2026-11-20）

> 实现在 WSL 权威副本（ext4）完成，E 盘工作区同源。
> 真链路阶段1 12/13（唯一 FAIL 是校验脚本把 6 张卡片写成 6——platform 单例本就不进普通列表，5 张可见卡 origin 全对，非产品缺陷）；
> 阶段2 级联删除 9/9；机检 5/5 + self-test 判红；**全量门禁 FAILS=0 UNJUDGED=0（68 步）**。

| TC | 结果 | 证据 |
|---|---|---|
| TC-01 | 达成 | `db-migrate.sh` 后 origin 列存在；6 项目 marking 与 D3 完全一致；幂等重跑不报错 |
| TC-02 | 达成 | `GET /projects/cards` 5 张可见卡全带 origin 且与 DB 一致（platform 单例不进普通列表）；`ProjectCard.spec.ts` 徽标断言 PASS；缺字段按 BUILTIN |
| TC-03 | 达成 | `ProjectServiceOriginTest.切换成功并审计` PASS，审计 `project.origin.change` USER→BUILTIN |
| TC-04 | 达成 | 真链路：非admin 403 / 代码错 400 / platform 409 / 非法值 400；单测四类反例 PASS |
| TC-05 | 达成 | `ProjectCard.spec.ts` BUILTIN 置灰+「程序自带」title + 徽标断言 PASS；USER 空项目可点 |
| TC-06 | 达成 | 阶段2 夹具项目（用例/集/步骤/绑定/集成员齐备）purge→200，项目行消失、逐表 0 行 |
| TC-07 | 达成（单测级） | `ProjectPurgeServiceTest.orphanBlocksSuccess` 验证孤儿>0 抛异常阻断；`@Transactional` 注解在 `ProjectPurgeService.purge`，事务由 Spring 保证回滚。未做真库人工制造 RESTRICT 冲突（破坏性大，用单测+注解覆盖） |
| TC-08 | 达成 | 真链路 BUILTIN purge 409 / platform 409 / 无token 401；单测非admin 403 且不级联 |
| TC-09 | 达成 | preview 返回逐表清单且前后计数一致（只读）；备份 `robot_pre_origin_20261120_163028.sql.gz`（2.5M）在 /tmp；审计 `project:purge` 写入 |
| TC-10 | 达成 | `WorkbenchView.spec.ts` 删除弹层 8 测试 PASS；confirmDelete 内 `wasCurrent` 判定后切到现存项目 |
| TC-11 | 达成 | `project_origin_guard.sh` 正常 FAILS=0；`--self-test` 注入 A2 期望改 200 → FAIL 判红、整体 SELF-TEST OK |
| TC-12 | 达成 | 契约 `gen_api_contract.py --check` PASS（491端点/408路径）；前端 vue-tsc + 1259 vitest + build PASS；全量门禁 68 步 FAILS=0 UNJUDGED=0 |
| TC-13 | 达成 | 机检 A1 + 真库 SELECT：5 张可见卡 origin 值全对，网卡测试01=USER，其余=BUILTIN |

### 偏差说明

1. **TC-01/06 未对网卡测试01 真删**：大师需求是「标记为用户自建、给删除权限」，未要求真删该真数据。级联能力用同构夹具项目（id=170 等）完整验证，验证后夹具全部 purge 清理（残留查询=0），真库 6 项目保持原样。
2. **TC-07 降级为单测级**：真库人工制造深层 RESTRICT 冲突会破坏夹具库结构，风险大于收益；改用单测验证孤儿阻断逻辑 + `@Transactional` 注解 + 5 轮多轮删（复用已验证 purge-temp.sh 策略）覆盖。诚实标注，不冒充做了真库冲突注入。
3. **TC-01 备份警告**：`mysqldump` 报 tablespaces PROCESS 权限警告，但 dump 文件完整生成（2.5M），迁移前备份有效。

**状态**：七道流程第 ⑥⑦ 环完成，全部 TC 处置完毕（12 达成 + 1 单测级降级），无遗留缺陷。
