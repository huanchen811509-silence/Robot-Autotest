# v5.0.0 发布说明

| 项 | 值 |
|---|---|
| 版本 | `5.0.0`（2026-11-12 落号） |
| 性质 | **存量功能优化打磨**；由 5.0.X 内部批次 B0~B4 收口而来 |
| 规模 | 30 条 FR / 30 个 WBS 包 / 42 条 TC；零新增表、零新增列、零新增迁移 |
| 兼容性 | 无破坏性 API 变更；既有端点签名不变；数据库 schema 不变 |
| 交付包 | 本轮**未重新生成 tarball**；需要时运行 `scripts/package-release.sh`，包名取 `VERSION` + 打包时 HEAD |
| 门禁 | 36 步 **35 PASS / 1 FAIL**，`GATES_RESULT FAILS=1`（唯一失败＝第 13 步 CDP `:9222`） |
| 判定状态 | **落号不等于判定**：G 门未判过，不产生对外口径，**不得对外称 GA** |

## 用户能拿到什么

- **口径可信**：i18n 2383 键、契约 98 张业务表、Flyway 最大 `V11_45`、README / 运维手册 / 版本地图全部对齐实读值，四项历史口径冲突由 `docs/plan/v5.0-口径裁决清单.md` 单一真源裁决。
- **关于页与版本迭代页说同一版**：`/api/_ping` 不再硬编码 `v0.1`，改读构建产物的 `app.version`；版本迭代页首条为 `v5.0.0` 并带当前版本标记。
- **机检进门禁**：新增 12 个机检脚本，门禁从 32 步扩到 36 步；每个脚本正向绿且 `--selfcheck` 真注入判红，静态契约组 11 项汇总进第 35 步。
- **可访问性静态整改**：24px 最小交互目标、`:focus-visible` 焦点环、六态结果字典单一真源、Top8 错误态清单与键盘走查清单。
- **治理留痕**：8 份 ADR、9 条质量属性场景、SLO / 错误预算规则、3 个恒 `false` 特性开关、AI 评测协议与空模板、人工欠账清单。

## 破坏性变更

| 项 | 说明 |
|---|---|
| 数据库 | **无**：零新增表、零新增列、零新增迁移；`exec-chain.sha256` 守门的 10 个双内核执行链文件零改动 |
| API | **无破坏性变更**：既有端点签名不变；`/api/_ping` 的 `version` 字段仍存在，值由固定字面量改为构建产物版本 |
| 执行链 | RF / pytest 双内核执行链、`RUN_ID` 与 metadata 装配器逐字未改 |
| 部署 | 需重建前端 dist 与后端 jar；`deploy.sh` 会重跑 `gen_build_info.py` 并同步 CHANGELOG 到 public 副本 |

## 已知限制（如实登记，不洗绿）

| 项 | 状态 | 依据 |
|---|---|---|
| 第 13 步浏览器回归（CDP `:9222`） | `FAIL`，按 D1 保持取消 | `docs/evidence/v5.0.0/raw/gates-32steps-baseline.json` |
| 新增 33~36 步 | `4 PASS / FAILS=0`，单独归档 | `docs/evidence/v5.0.0/raw/gates-4steps-new.json` |
| Top8 成功率 / SUS / 长列表 fps / 运行链 createP95 / 真实端点事实一致性 | **未测量** | 基线 JSON 一律 `status=未测量`、`sample_size=0`；禁止填 0 或折算 |
| 键盘 / 读屏 / 200% 缩放人工走查、暗色下拉与密度探针 | `NOT-RUN` | 9 支体验探针依赖 CDP；静态机检不折算人工 PASS |
| 六个现场包 | `BLOCKED` | ADR-0013 禁跑令未解禁 |
| 隔离环境回滚演练与 RTO 计时 | `BLOCKED` | 按 D5 不启用演练环境 |
| AI 真实端点评测 | `NOT-RUN` | 按 D6 未授权，只交协议与空模板 |
| `resultStates.ts` 三处引用迁移 | `PARTIAL` | 六态真源已落，引用未全量迁移 |
| 六项目标 | 5 项部分达成，智能化按裁定不执行 | **本版不产出量化体验提升结论** |

## 修复

- **关于页版本口径漂移**：`PingController` 的 `version` 由硬编码 `v0.1` 改为 `BuildInfoService.info()` 的构建产物版本，并补单测断言版本非空且不再为 `v0.1`。
- **版本迭代页看不到本批次**：`v5.0.0` 已作为正式 `## ` 版本标题写入 CHANGELOG，四类小节齐全、当前标记唯一且排在第一，解析器可正常渲染。
- **README / 运维手册陈旧真值**：版本号、门禁步数、契约路径数、systemd unit 与实测端口全部回写。

## 门禁读数

| 范围 | PASS | FAIL | 汇总 | 归档 |
|---|---|---|---|---|
| 原 32 步（判据不动） | 31 | 1 | `FAILS=1` | `gates-32steps-baseline.json` |
| 新增 33~36 步 | 4 | 0 | `FAILS=0` | `gates-4steps-new.json` |

两份读数**分开归档，不并成「全绿」**；42 条 TC 为 PASS 29 / PARTIAL 8 / NOT-RUN 4 / BLOCKED 1 / FAIL 0，结果只写 `05-TEST-REPORT`。

## 升级步骤

1. 备份现有 `backend/target/*.jar` 与 `frontend/dist/`，以及数据库。
2. `git fetch` + `git merge --ff-only` 到落号提交，`cat VERSION` 应为 `5.0.0`。
3. `bash scripts/deploy.sh`（内部会重跑 `gen_build_info.py`、同步 CHANGELOG、重建前端与后端并重启三个 unit）。
4. `curl -s --noproxy '*' http://127.0.0.1:8001/healthz` 与 `http://127.0.0.1:8001/api/health` 均应 UP。
5. `python3 tools/check_version_consistency.py` 确认 `VERSION` / build-info / OpenAPI / 两份 CHANGELOG / 运行期六处同源。
6. `bash scripts/gates.sh` 看门禁读数；第 13 步 CDP 未恢复前如实 `FAILS=1`。
7. 复核「关于」页显示 `5.0.0`、版本迭代页首条为 `v5.0.0`。

## 回滚

- **代码**：`git revert <落号提交>` 或回退到 `830c3b8`，重跑 `deploy.sh`；`VERSION` 会回到 `4.12.0`。
- **数据**：本版无迁移，无需 down 脚本。
- **配置**：3 个 v5.0 开关恒 `false`，无开关需回拨；`FEATURE-FLAGS.md` 保留登记。
- **注意**：回滚 jar 后必须重跑 `gen_build_info.py`，否则「关于」页与运行期 build-info 仍报新 commit。

## 判定与对外口径

> **落号不等于判定**。本版已完成机械落号与本地部署，但 G4 / G5 / G7 / G8 等门禁**仍未判过**，
> 真库 down→up 全量演练、真实 DUT / 负载与 G6 观察钟均未执行；**不得对外称 GA、不产生对外口径**。
> 逐门书面核验见 `docs/plan/门禁核验纪要-v5.0.0-20261112.md`，**判定动作与对外发布归大师**。
