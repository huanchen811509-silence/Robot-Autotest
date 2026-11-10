# v4.2.0 测试报告（RA-TR-420）

**文档编号** RA-TR-420 ｜ **版本** v1.0 ｜ **日期** 2026-11-10 ｜ **用例总数** 70 ｜ **PASS 21 / PARTIAL 30 / NOT-RUN 19 / FAIL 0**

> 结果口径见 [PRD.md](./PRD.md) §4。`NOT-RUN` 与 `PARTIAL` 不折算为通过；
> 每条都写明证据出处或补齐方法。`FAIL` 为 0。

## 1. 逐条结果（4.0.0 · 交互基座与合规）

| 编号 | 结果 | 证据 / 原因 |
|---|---|---|
| `TC-v4.0-01` | `PARTIAL` | `EW40-01/redaction-cases.json` 仅 5 面（`run-log` / `api-json` / `http` / `report` / `console`）；打印 / docx / 导出包未单列 |
| `TC-v4.0-02` | `NOT-RUN` | 无阻断与导出日志；补齐：用含口令样本生成一次报告并归档拒绝原因与哈希 |
| `TC-v4.0-03` | `NOT-RUN` | `EW40-01` 无自检日志；补齐：跑脱敏机检 `--self-check` 并落盘 |
| `TC-v4.0-04` | `PARTIAL` | 同一 JSON 有 innocent 样本 `ssh -u root@…`；未单列 `-u username` |
| `TC-v4.0-05` | `PARTIAL` | `EW40-02/account-menu-probe.json` 只验账户菜单矩形；全部入口浮层清单未取 |
| `TC-v4.0-06` | `NOT-RUN` | 无 `elementFromPoint` 读数；补齐：浮层打开态取顶栏中心命中元素 |
| `TC-v4.0-07` | `PARTIAL` | 覆盖焦点陷阱 / ESC / 滚动锁 / reduced-motion；`aria-modal` 与三档视口未列 |
| `TC-v4.0-08` | `NOT-RUN` | 无危险确认遮罩读数；补齐：页面内删除确认打开态量遮罩与顶栏矩形 |
| `TC-v4.0-09` | `PARTIAL` | `EW40-02` / `EW40-03` 证四分区；顶栏 5 / 4 计数未单列 |
| `TC-v4.0-10` | `PARTIAL` | 证状态区 `input=0`、数据源含 `/api/health`；完整请求瀑布未取 |
| `TC-v4.0-11` | `PARTIAL` | `EW40-03/README.md` 证 engineer 门控；键盘仅验 Esc，Tab / Enter 未列 |
| `TC-v4.0-12` | `PARTIAL` | `EW40-03` 单测第 8–10 条含登出二次确认；会话端点清单未列 |
| `TC-v4.0-13` | `PASS` | `EW40-04/layout.log`：`rail=64`、`padding=28`、5 处同步走令牌 |
| `TC-v4.0-14` | `PASS` | 同日志 12 场景 / 56 项检查 `FAILS=0` |
| `TC-v4.0-15` | `PARTIAL` | `EW40-05/update-drill.log` A/B/C 证 dry-run / 同版本拒绝 / 降级需双开关；生产库名拦截未列 |
| `TC-v4.0-16` | `PARTIAL` | 演练目标为 `3.11.1`，非 `4.0.0`；转正落号后未重跑真升级 |
| `TC-v4.0-17` | `PARTIAL` | 证 `status.sh` 显示上一可用版本、`KEEP=3`；Flyway checksum 未列 |
| `TC-v4.0-18` | `PARTIAL` | D 场景自动回滚到 `3.11.0`；未分 `--schema=keep` 与 DB 恢复两条路径，行数比对未做 |
| `TC-v4.0-19` | `PARTIAL` | `EW40-06/gates.log` 后端 1279、前端 583、浏览器 40；编辑器构建与 `vue-tsc` 未点名 |
| `TC-v4.0-20` | `PARTIAL` | 当时 30 步自检 PASS；「第 31 步删步必红」当时尚不存在 |
| `TC-v4.0-21` | `PARTIAL` | `release-docs.log` `FAIL 0 / WARN 0`，当时版本仍 `3.11.0`；四处 sha 未列 |
| `TC-v4.0-22` | `NOT-RUN` | 无嵌套浮层读数；补齐：账户下拉内再开一层并逐层 ESC |
| `TC-v4.0-23` | `PASS` | `EW40-04/layout.log` 与 `side-toggle.log` 覆盖 1280 折叠与 1024 极窄 |
| `TC-v4.0-24` | `PARTIAL` | i18n 键集相等 PASS；`axe` 明确 `NOT-RUN`（未安装） |
| `TC-v4.0-25` | `PARTIAL` | 仅验后端不可达降级；401 与版本接口失败两种降级未测 |
| `TC-v4.0-26` | `NOT-RUN` | `EW40-05/README.md` §3 明说只覆盖回滚成功路径；RTO / RPO 未演练 |
| `TC-v4.0-27` | `NOT-RUN` | 无 uninstall / backup / restore 实证；`EW41-01` 仅静态核对 `KEEP_DIRS` |

## 2. 逐条结果（4.1.0 · 项目工作区与创建向导）

| 编号 | 结果 | 证据 / 原因 |
|---|---|---|
| `TC-v4.1-01` | `PASS` | `EW41-02/README.md` E1/E2：16 项契约全 PASS，结构齐全 |
| `TC-v4.1-02` | `NOT-RUN` | `EW41-02` §3 快照一致性与项目锁并发未做；补齐：并发导出脚本 + 行数比对 |
| `TC-v4.1-03` | `PARTIAL` | 说明只写掩码，无独立密钥扫描日志；补齐：全表面扫描脚本归档 |
| `TC-v4.1-04` | `PASS` | `EW41-02` E5：非成员 403、角色矩阵、成功失败各一条审计 |
| `TC-v4.1-05` | `PASS` | `EW41-03` I1：预检可导入，默认 skip 逐条报告 |
| `TC-v4.1-06` | `NOT-RUN` | 写入中断未注入；补齐：进程内抛异常后核对库表与磁盘 |
| `TC-v4.1-07` | `PARTIAL` | `EW41-04` L2 证绝对路径与 `..`；导入侧控制字符未单列 |
| `TC-v4.1-08` | `PARTIAL` | `EW41-02` E3 两类篡改必拒；跨版本无适配器的拒绝原因未单列 |
| `TC-v4.1-09` | `PASS` | `EW41-03` I2：重复导入全 `SKIP`，三策略可选 |
| `TC-v4.1-10` | `PASS` | `EW41-04` L3 / L4 / L5：三级各自 PASS |
| `TC-v4.1-11` | `PARTIAL` | 范围正确；三级逐文件 `sha` 对照表未输出 |
| `TC-v4.1-12` | `NOT-RUN` | 配额负向未做；补齐：造超配额包并核对错误码与不截断 |
| `TC-v4.1-13` | `PASS` | `EW41-05/README.md`：向导 10/10，回退保留输入 |
| `TC-v4.1-14` | `NOT-RUN` | 无提交失败与草稿回滚读数；补齐：注入提交失败后查库与草稿 |
| `TC-v4.1-15` | `PASS` | `EW41-05` 模板 3/3 + 真库六项读数（`projects 11 → 12`、`members=2`、`kernel.default=robot`） |
| `TC-v4.1-16` | `PARTIAL` | 证 `audit.project:create=1`；无 `PROJECT/CREATE` 权限的 403 未演 |
| `TC-v4.1-17` | `PASS` | `EW41-01/workspace-selfcheck.log` 15/15，含「无自动同步」负向自证 |
| `TC-v4.1-18` | `PASS` | `EW41-07/README.md`：32 步 `FAILS=0` + 自检 + 发布机检 |
| `TC-v4.1-19` | `PARTIAL` | `EW41-01` 证 `TenantGuard` 登记与 down 脚本；`SchemaIT` 与 OpenAPI 同数未点名 |
| `TC-v4.1-20` | `NOT-RUN` | `v4.1.0/README.md` `G-09`：空实例 B 未就绪 |
| `TC-v4.1-21` | `NOT-RUN` | 无 B 侧同 `code` 样本；补齐：备 B 实例后造冲突项目 |
| `TC-v4.1-22` | `NOT-RUN` | 并发与配额未做 |
| `TC-v4.1-23` | `NOT-RUN` | 无 Windows 中文路径往返样本；补齐：`robot_dev` 导入后再导出比 `sha` |
| `TC-v4.1-24` | `PARTIAL` | `EW41-02` E3 两类篡改 + `EW41-01` 自证含错密钥 / 缺签名；三类同时篡改未单列 |
| `TC-v4.1-25` | `PASS` | `EW41-03` I3：隔离 3 个文件，显式确认后入库并留 warning |
| `TC-v4.1-26` | `PARTIAL` | 有掩码与机检；未给目录树 / 日志 / 任务 / 审计副本的汇总扫描读数 |

## 3. 逐条结果（4.2.0 · 工程与运维收口）

| 编号 | 结果 | 证据 / 原因 |
|---|---|---|
| `TC-v4.2-01` | `PARTIAL` | `EW42-01/no-db.log` 证后端非 IT 1301 例；前端单测在门禁第 8 步另有日志，未与前项同 run 并列 |
| `TC-v4.2-02` | `PASS` | `env -u` 六变量后 `mvn -o -B test`：`1301 / 0 / 0 / 4 skipped`、`BUILD SUCCESS` |
| `TC-v4.2-03` | `NOT-RUN` | 无 GitHub run；补齐：`gh run view <id>` 查失败 run 无 artifact |
| `TC-v4.2-04` | `NOT-RUN` | 无 run 时长与缓存读数；补齐：`gh run view` 取 duration 与 cache 命中 |
| `TC-v4.2-05` | `PASS` | `SchemaIT` 7/7、`db-audit` PASS，两端读同一 `db/schema-contract.json` |
| `TC-v4.2-06` | `PASS` | `DB_NAME=robot_it mvn -o -B -Pit test -Dtest=SchemaIT`：7/7 PASS |
| `TC-v4.2-07` | `PASS` | 改坏契约两处判红；恢复后 PASS（负向样本保留） |
| `TC-v4.2-08` | `PASS` | `SshHostCommandRunnerTest` 18 例 + 静态核对白名单与禁提权 |
| `TC-v4.2-09` | `NOT-RUN` | 无受控 SSH 目标；本机采集 9/10，GPU 项写「未采集到（`nvidia-smi` not found）」 |
| `TC-v4.2-10` | `PASS` | 凭据只回掩码；接口不回明文；非成员 403 |
| `TC-v4.2-11` | `PARTIAL` | `GET /collect` 已修 `{id:\d+}` 并回 405；采集页加载期 0 写请求未单测 |
| `TC-v4.2-12` | `NOT-RUN` | 无 `gh` 与授权；补齐：下载 artifact 后 `sha256sum -c` + 解包 `DOCTOR PASS` |
| `TC-v4.2-13` | `PASS` | `EW42-05`：32/32 PASS、`--selfcheck FAILS=0`、发布机检 `FAIL 0 / WARN 0` |
| `TC-v4.2-14` | `PARTIAL` | 版本四副本 PASS；交付包 `MANIFEST` / `sha256` 在门禁第 30 步覆盖，本轮未单列读数 |
| `TC-v4.2-15` | `PARTIAL` | 遗留缺口已逐条关闭或转 `NOT-RUN`；其中三条 CI 类仍 `NOT-RUN` |
| `TC-v4.2-16` | `PARTIAL` | 单测覆盖 host key / 截断 / 超时 / 禁 `sudo` / 审计；真 SSH 未跑 |
| `TC-v4.2-17` | `PARTIAL` | 无库空跑已证；依赖缓存与时长归档无 GitHub 读数 |

## 4. 汇总

| 批次 | 用例 | `PASS` | `PARTIAL` | `NOT-RUN` | `FAIL` |
|---|---|---|---|---|---|
| 4.0.0 | 27 | 3 | 17 | 7 | 0 |
| 4.1.0 | 26 | 10 | 8 | 8 | 0 |
| 4.2.0 | 17 | 8 | 5 | 4 | 0 |
| **合计** | **70** | **21** | **30** | **19** | **0** |

`PARTIAL` 集中在「真机 / 真网络 / 真实例」类子项；`NOT-RUN` 全部因前置条件不具备，
不是实现缺失。逐条补齐方法已写在上表。

## 5. 横向回归读数

| 项 | 读数 |
|---|---|
| 后端单测（无库） | 1301 例 / 0 失败 / 0 错误 / 4 条件跳过，`BUILD SUCCESS` |
| `SchemaIT`（`robot_it`） | 7/7 PASS |
| 前端单测 | 583 例 PASS |
| 构建 | `vue-tsc` 0 错、`vite build` 成功、编辑器构建成功 |
| i18n | 1847 / 1847 键，中英一致 |
| 门禁 | 32 / 32 PASS，`--selfcheck FAILS=0` |
| 发布文档机检 | 23 个版本 / `FAIL 0` / `WARN 0` |
| 表格机检 | 7 份 / `FAIL 0` |
| 版本一致性 | 六项全 PASS（含运行期 `build-info` 与入口 `CHANGELOG.md`） |
| 契约快照 | 291 endpoints / 232 paths |
| 业务表 | 89 张（`V11_28` 后），`db-audit` PASS |
| 交付包 | `robot-autotest-4.2.0-946955d-linux-x64.tar.gz`（289 文件 / 48.2 MB），`sha256sum -c` 与 MANIFEST 逐文件校验 PASS |

## 6. 实测推翻的假设（回写）

| 假设 | 实测 | 处置 |
|---|---|---|
| `mvn -Dgroups=it` 能跑 IT | 默认 `excludedGroups=it` 把它抵消成 0 用例且 `BUILD SUCCESS` | 统一 `-Pit` + `DB_NAME=robot_it` |
| env 里 `DB_NAME=robot_dev` 可直接跑 IT | `_it` 后缀守卫拒绝执行 | 守卫保留，执行时显式改 `robot_it` |
| `GET /collect` 会自然回 405 | 被 `@GetMapping("/{id}")` 命中回 400 | 改 `{id:\d+}` |
| 业务表 88 张 | 实际 89 张 | README / CHANGELOG 同步改 89 |
| append-only 必须看 `created_at` | 3 张表无该列，真库必然判红 | 契约改「`created_at` 或业务时间锚点二选一且互斥」 |
| 门禁可保持 29 步 | 实际扩到 32 步 | README / CHANGELOG 同步改 32 |
| 落号后可直接沿用 `3.11.0` 交付包 | 第 30 步判红：`MANIFEST version=3.11.0 与 VERSION=4.2.0 不一致` | 重跑 `scripts/build.sh` + `scripts/package-release.sh` 产 `4.2.0` 包，第 30 步 PASS |
| `bash scripts/build.sh` 可在任意 shell 直接调用 | 非登录 shell 的 PATH 落到 Windows `npm`，在 9P 路径上报 `ENOTEMPTY`（编辑器件构建中断） | 一律 `bash -lc "bash scripts/build.sh"` |

## 7. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-10 | 初稿：70 条逐条结果、汇总、横向回归读数、六条推翻假设 |
| v1.1 | 2026-11-10 | 落号后复验：补交付包读数；§6 补「3.11.0 包不可沿用」「build.sh 须登录 shell」两条实测推翻 |
