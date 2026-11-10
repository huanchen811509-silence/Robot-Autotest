# v4.2.0 发布说明（RA-RN-420）

**版本** `4.2.0` ｜ **日期** 2026-11-10 ｜ **构建** `946955d`（`2026-11-10T00:46:43Z`） ｜ **发布类型** 三批合并落号（4.0.0 / 4.1.0 / 4.2.0 一次交付）

> 本说明面向部署与使用方，只讲「拿到什么、怎么升、有什么限制」。
> 需求与验收细节见同目录 [PRD.md](./PRD.md)、[TEST-PLAN.md](./TEST-PLAN.md)、[05-TEST-REPORT.md](./05-TEST-REPORT.md)；
> 工作包与顺序见 [DEV-PLAN.md](./DEV-PLAN.md) 与 `docs/plan/v4.x-总体演进-开发计划-20261109.md`。
> **落号不等于判定**：G 门与对外口径仍归大师，本说明只报实装与机检结论。

## 1. 一句话概览

命令里的口令不再出现在任何输出面；顶栏浮层、账户下拉、折叠态留白统一到「工作区浮层」范式；
项目变成可导出 / 可校验 / 可导入 / 可分级传递的工作区，并可用 4 套模板从向导新建；
升级可 dry-run 演练并能自动回滚；CI 无库也能判红绿，Schema 判据单源，远程主机可只读采集。

## 2. 本次交付内容（14 条 FR）

### 2.1 交互基座 + 合规 + 更新闭环（4.0.0）

| 能力 | 用户可见变化 |
|---|---|
| 命令脱敏（`FR-v4.0-01`） | 报告 / 打印 / docx / API / 审计 / 运行日志 / 控制台 / 导出包**八面明文 0 命中**；新增覆盖 `-u user:pass` 写法，`-u username` 等合法写法不误脱敏 |
| 工作区浮层范式（`FR-v4.0-02`） | 浮层 `top >= header.bottom`，遮罩不盖顶栏；嵌套浮层逐层 ESC、焦点归还、滚动锁与 `reduced-motion` 生效 |
| 账户下拉（`FR-v4.0-03`） | 状态 / 外观 / 管理 / 会话四分区；状态区只读、加载期 0 写请求；非 admin 不渲染管理入口；登出不健康 / 401 / 版本接口失败均有降级 |
| 折叠态留白（`FR-v4.0-04`） | 窄轨 `56 → 64px`、右内边距 `20 → 28px`，5 处同步走令牌；1024 / 1280 / 1440 / 1920 与 125% / 150% 缩放无横滚 |
| 可回滚升级（`FR-v4.0-05`） | `update.sh` 默认 dry-run，`--apply` 需输入 `UPDATE` 确认；同版本拒绝、降级需 `--force --restore-db`、生产库名拦截；失败自动回滚，保留最近 3 版 |

### 2.2 项目工作区与创建向导（4.1.0）

| 能力 | 用户可见变化 |
|---|---|
| 工作区导出（`FR-v4.1-01`） | 产出 `project.json` + `manifest.json`（逐文件 `sha256` 与字节数）+ 7 个子目录 + `members.json`；非成员 403，全文本面密钥扫描 0 命中 |
| 工作区导入（`FR-v4.1-02`） | dry-run 先列冲突，默认 `skip` 逐条报告；单事务、失败整批回滚；`skip` / `overwrite` / `rename` 三策略幂等；篡改 `sha`、未验签、路径穿越、非白名单扩展名全拒并给原因 |
| 三级传递与配额（`FR-v4.1-03`） | 整项目 / 子目录 / 单文件三粒度各自可用；单文件 `sha` 与源一致、子目录不夹带；超配额明确报错、不静默截断 |
| 创建项目向导（`FR-v4.1-04`） | 5 步（viewer 4 步）每步可回退且无副作用；4 套版本化 JSON 模板；提交后项目 / 成员 / 用例集 / `kernel.default` 读数一致 |
| 工作区 UI 与任务中心（`FR-v4.1-05`） | 导出 / 导入预检 / 任务三视图统一到项目 `DataTable` 壳；任务失败行可单独重试，重试真实调用后端 |

### 2.3 工程与运维收口（4.2.0）

| 能力 | 用户可见变化 |
|---|---|
| CI 无 DB 质量门（`FR-v4.2-01`） | 构建前跑后端非 IT 全量（1301 例）与前端单测；不连 MySQL、不启 CDP，`DB_PASSWORD` 不入 workflow |
| Schema 判据同源（`FR-v4.2-02`） | `SchemaIT` 与 `db-audit` 直读 `db/schema-contract.json`；改一处另一处必判红 |
| 远程主机只读采集（`FR-v4.2-03`） | 凭据 + `confirm=true` 才连远端；`StrictHostKeyChecking=yes`、`BatchMode=yes`、拒绝 `sudo` / `su` / `doas`、口令只进 `SSHPASS`、单条输出上限 256 KiB、超时终止，没采到写原因 |
| artifact 端到端复核（`FR-v4.2-04`） | 下载 tarball → `sha256sum -c` → 解包 `DOCTOR PASS`，三件同时成立才关闭 `G-05`（本次未具备 CI 条件，见 §6） |

## 3. 升级须知

1. **数据库**：新增迁移 `V11_28`（新建工作区相关表），附 `db/down/V11_28*.down.sql`，`TenantGuard` 已登记；业务表 **89** 张，`db-audit` PASS。已应用的历史迁移未改动。
2. **升级步骤**（安装根下）：
   - 备份：`bin/backup.sh`（备份范围已含 `workspace/`）；
   - 演练：`bin/update.sh --package /path/to/robot-autotest-4.2.0`（默认 dry-run，只列迁移清单）；
   - 执行：`bin/update.sh --package /path/to/robot-autotest-4.2.0 --apply`，按提示输入 `UPDATE`；
   - 复核：`bin/status.sh` 查看当前版本、上一可用版本与服务状态。
3. **`workspace/` 属运行数据**：不入库（`.gitignore` 含 `/workspace/`），`uninstall.sh` 的 `KEEP_DIRS` 保留该目录，`backup.sh` 纳入备份；只读文件系统会明确报错。
4. **源码部署**（非交付包）：`npx vite build` 重建 `frontend/dist`，根 `CHANGELOG.md` 同步到 `frontend/public/CHANGELOG.md`，`scripts/gen_build_info.py` 重写 `build-info.properties`。
5. **门禁**：由 29 步扩到 **32 步**；`bash scripts/gates.sh --selfcheck` 需在登录 shell（`bash -lc`）下跑。

## 4. 兼容性与破坏性变更

| 项 | 结论 |
|---|---|
| API 路径 / 包名 | **未改**；仅新增端点（OpenAPI 快照由 284 endpoints / 225 paths 扩到 **291 / 232**） |
| 破坏性变更 | **无** |
| 数据库 | 仅新增 `V11_28` 表与 `down` 脚本，遵循 expand-contract；上一版二进制在新 schema 上仍可启动 |
| 版本号 | `3.11.0` → `4.2.0` 属计划内**跨版本合并落号**（4.0.0 / 4.1.0 不单独发版）；`VERSION` / `build-info.properties` / 根与前端 `CHANGELOG.md` / `openapi.json` 四处一致，运行期 `/api/system/build-info` 与入口 `/CHANGELOG.md` 实测同为 `4.2.0` |
| 环境依赖 | Java 21 / Spring Boot 3.3.5 / MySQL 8.0；无新增外部服务依赖 |

## 5. 交付物与校验

| 交付物 | 读数 |
|---|---|
| `robot-autotest-4.2.0-946955d-linux-x64.tar.gz` | 289 文件 / **48,228,319 B**（约 46.0 MiB） |
| `…tar.gz.sha256` | `a22b81c2a0bb1d1c1299cc2dc3ac0c38e92a5789d7b98886402aba2a14ed9994` |
| `…manifest.json` | 逐文件路径 / `sha256` / 字节数，51,011 B |
| 校验命令 | `sha256sum -c robot-autotest-4.2.0-946955d-linux-x64.tar.gz.sha256` → **PASS** |

**发版前机检读数**（2026-11-10，WSL 权威副本复跑）：

| 门 | 读数 |
|---|---|
| 全量门禁 | `scripts/gates.sh` → **32 / 32 PASS，`FAILS=0`** |
| 门禁自检 | `--selfcheck` → `FAILS=0` |
| 后端单测（无库） | **1301 例 / 0 失败 / 0 错误 / 4 条件跳过**，`BUILD SUCCESS` |
| 集成（`SchemaIT`，`robot_it`） | **7 / 7 PASS** |
| 前端单测 | **583 例 PASS**；`vue-tsc` 0 错、`vite build` 成功、编辑器构建成功 |
| i18n | **1847 / 1847** 键，中英一致 |
| 发布文档机检 | 23 个版本 / `FAIL 0` / `WARN 0` |
| 表格机检 | 7 份文档 / `FAIL 0` |
| 版本一致性 | 六项全 PASS |
| 契约快照 | 291 endpoints / 232 paths |
| 业务表 | 89 张（`V11_28` 后），`db-audit` PASS |
| 测试结果 | 70 条 TC：**21 PASS / 30 PARTIAL / 19 NOT-RUN / 0 FAIL** |

## 6. 已知限制（不折算为通过）

| 限制 | 影响 / 补齐方法 |
|---|---|
| **CI 真实流水线未跑**：无 GitHub self-hosted runner、无 `gh` 授权 | `TC-v4.2-03/04/12` 为 `NOT-RUN`，`G-05`（artifact 端到端）**未关闭**；`actionlint` 与无库空跑已在本地通过，注册 runner 后按 DEV-PLAN 复跑即可 |
| **远程 SSH 真机采集未跑**：无受控目标主机 | `TC-v4.2-09` 为 `NOT-RUN`、`TC-v4.2-16` 为 `PARTIAL`；单测 18 例与契约 29 项已过，接入 `known_hosts` 指纹后补真机读数 |
| **升级演练目标为 `3.11.1`，未在落号后的 `4.2.0` 包上重跑** | `TC-v4.0-16` 为 `PARTIAL`；生产库升级须大师单独授权，不属发布动作 |
| **30 条 `PARTIAL`** 集中在真机 / 真网络 / 真实例子项 | 逐条缺哪一项与补齐方法写在 `05-TEST-REPORT.md` §1–§3 |
| **19 条 `NOT-RUN`** 因前置条件不具备 | 同上，不折算为通过；`FAIL` 为 0 |
| **G 门未申请** | 发版判定与对外口径仍归大师 |

## 7. 回滚

- 升级失败由 `bin/update.sh` 自动回滚到上一可用版本（保留 3 版）。
- 人工处置与数据恢复路径见 `docs/ROLLBACK.md`；生产库回滚需 `--force --restore-db` 显式声明确认。

## 8. 后续（未进本批）

候选 `FR-v4.2-05` ～ `FR-v4.2-08` 按 DEV-PLAN「顺序纪律」第 4 条**未纳入本批**，需授权后另开工作包与 TC 编号段。
`v3.12+` 三份计划保留为历史审计轨迹，已被 4.X 收编。

## 9. 文档索引

| 文档 | 用途 |
|---|---|
| [00-RESEARCH.md](./00-RESEARCH.md) | 调研与问题定位 |
| [01-ASSESSMENT.md](./01-ASSESSMENT.md) | 评估与范围（14 条 FR） |
| [PRD.md](./PRD.md) | 需求、设计决策、三档验收口径 |
| [DEV-PLAN.md](./DEV-PLAN.md) | 19 个工作包、迁移与部署、门禁与取证 |
| [TEST-PLAN.md](./TEST-PLAN.md) | 70 条用例与执行口径 |
| [05-TEST-REPORT.md](./05-TEST-REPORT.md) | 逐条结果、汇总、横向回归读数、实测推翻假设 |
| [RELEASE-NOTES.md](./RELEASE-NOTES.md) | 本说明（面向部署与使用方） |

## 10. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-10 | 初稿：三批内容、升级须知、兼容性、交付物校验、已知限制与回滚 |
