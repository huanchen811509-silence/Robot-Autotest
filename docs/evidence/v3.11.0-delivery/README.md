# v3.11.0 交付能力增强 · 证据索引

| 项 | 内容 |
|---|---|
| 目录 | `docs/evidence/v3.11.0-delivery/` ｜ 2026-11-09 |
| 对应 | 大师 2026-11-09 交办 6 项；`docs/plan/安装拆卸运维能力评估-20261109.md` 的 TC-D01..D08 |
| 口径 | **未 bump 版本号、未创建 GitHub Release、未判 G 门**；`VERSION` 仍为 `3.11.0` |
| 结论 | 6 项逐项有证据；本地交付包、真实安装/拆卸、机检与门禁均 PASS；CI 侧结论见 §4 |

---

## 1. 证据清单

| 文件 | 判了什么 | 由谁产出 |
|---|---|---|
| `bundle-manifest.json` | 交付包逐文件清单 / 282 个文件 / 57 MB / `secrets=none` / 五个入口命令 | `scripts/package-release.sh` |
| `bundle.sha256` | tarball 的 sha256（可 `sha256sum -c` 复核） | 打包脚本 |
| `bundle-name.txt` | 本次 tarball 文件名 | 验证脚本 |
| `vite-build.log` | 前端重建成功（`dist/` 带上最新 CHANGELOG 与关于/版本迭代页） | `npx vite build` |
| `package-check.log` | 22 项打包前置全部 PASS | `package-release.sh --check` |
| `package.log` | `PACKAGE PASS name=... files=282`，tarball 48 MB | `package-release.sh` |
| `contract.log` | `DELIVERY_BUNDLE PASS fails=0`（静态契约 + 真实 tarball 实证） | `tools/check_delivery_bundle.py` |
| `unpack-verify.log` | 解包后 `DOCTOR PASS fails=0`（12 项）、安装 dry-run、MANIFEST 校验 | 验证脚本 |
| `install-apply.log` | 真实 `install.sh --apply`：282 个文件落盘，凭据文件未改写 | `wb-install-uninstall-verify.sh` |
| `uninstall-dryrun.log` | dry-run 不删任何文件 | 同上 |
| `uninstall-apply.log` | 真拆卸：12 项逐项删、备份 20K、留 `REMOVED.txt`、数据库不动 | 同上 |
| `md-tables.log` | 8 份文档 / 53 个表格块 / 393 行，列数一致 | `check_md_tables.py` |
| `ci-run.json` | GitHub Actions 真实运行结论（run id / 结论 / artifact / annotations） | `wb-ci-evidence.py` |
| `ci-run.log` | `gh run view` 原文（job 与步骤清单） | `gh run view` |
| `download-probe.log` | artifact 下载链路：元数据 / 两次全量尝试 / Range `HTTP 206` + ZIP 魔数 | `gh api` + `curl` |

> `releases/*.tar.gz` 本身按 `.gitignore` 的 `/releases/` 规则**不入库**（可由脚本重建）；
> 入库的是 MANIFEST、校验和与上述日志，保证 clone 后仍可复核「包里该有什么」。

---

## 2. 六项交付逐项结论

| # | 大师交办 | 结论 | 证据 |
|---|---|---|---|
| 1 | 更新关于和版本迭代 | PASS | `CHANGELOG.md` 修历史「`VERSION` 仍为 3.4.0」+ 追加未发版工作记录；`AboutView.vue` 增交付与运维卡、`ChangelogView.vue` 增提示；`AboutView.spec.ts` 4/4；i18n 1687/1687 |
| 2 | 优化 README 排版 | PASS | 单行数百字宽表拆成 4 张分组表；状态区更新到 3.11.0 / 30 步 / 88 表 / 284 endpoints / 1687 键；`md-tables.log` |
| 3 | 同步 GitHub 与群晖 | PASS | 见 §5 四处 sha |
| 4 | 评估安装/拆卸/运维 | PASS | 评估报告（13 事实 / 5 推断 / 4 假设 / 5 方案 / 8 风险）+ 9 个 `scripts/ops/` 脚本 + `DELIVERY-GUIDE.md` + 真安装/拆卸验证 |
| 5 | GitHub Actions CI 构建交付包 | PASS | `.github/workflows/delivery.yml`、`bundle-manifest.json`、§4 CI 结论 |
| 6 | 本轮收尾 + 下一轮 PRD/开发测试计划 | PASS | 下一轮三份 `docs/plan/v3.12+交付运维演进-*`、本报告、§5 收口 |

---

## 3. 机检与门禁读数

| 判据 | 读数 |
|---|---|
| 交付包机检自校验 | `SELFCHECK_RESULT FAILS=0`（8 类脏数据判红 / 干净骨架判绿） |
| 交付包静态 + 产物实证 | `DELIVERY_BUNDLE PASS fails=0` |
| 门禁结构自校验 | `SELFCHECK_RESULT FAILS=0`（含「删第 30 步必变红」） |
| 打包前置 | `PACKAGE_CHECK PASS fails=0`（22 项） |
| 解包体检 | `DOCTOR PASS fails=0`（12 项 PASS / 0 WARN / 0 FAIL） |
| 真实安装 / 拆卸 | `INSTALL_UNINSTALL PASS`；服务前后 `:8081=200/200`、`:8001=200/200`；凭据文件 sha256 前后一致 |
| 文档机检 | 发布机检 `FAIL 0 / WARN 0`；表格 `53 块 / 393 行 PASS`；i18n `1687/1687`；版本一致性 `PASS (0 项)` |
| 全量门禁 | 见 `docs/evidence/v3.11.0-delivery/final-gates.log`（归档到入库目录，避免 `logs/` 被忽略） |

---

## 4. CI 真实运行结论

| 项 | 值 |
|---|---|
| workflow | `delivery.yml`（GitHub-hosted `ubuntu-latest`） |
| 触发方式 | push main，commit `d6016b1` |
| run id | `42595970100` / job `129088544961` |
| 结论 | **success**，耗时 1m49s，10 个业务步骤全 ✓、0 失败 |
| artifact | `robot-autotest-linux-x64`，id `13663192944`，48,130,253 字节，保留 30 天 |
| 下载 | `gh run download 42595970100 -n robot-autotest-linux-x64` |
| 证据 | `ci-run.json`（结构化）、`ci-run.log`（`gh run view` 原文） |

CI 步骤实证：`Checkout → Setup JDK 21 → Setup Node 22 → scripts/build.sh →
check_delivery_bundle.py → package-release.sh --check → package-release.sh →
解包自检（doctor + 安装 dry-run）→ upload-artifact → Summary`。
既有 `PR` workflow 同批 push 仍 `queued`（self-hosted `rf-lab` 未注册），保持不动。

**三条 annotation（不影响结论，登记为下一轮附带项）**：
`actions/*@v4` 的 Node 20 被强制跑到 Node 24；`setup-java@v4` 建议升 v5；
`ubuntu-latest` 镜像将迁到 Ubuntu 26（交付包无原生二进制，跨 Ubuntu 版本可用）。

> **下载链路实证**（`download-probe.log`）：Range 请求返回 `HTTP 206 / 2048 字节 /
> PK ZIP 魔数 50 4b 03 04`，证明 artifact 可下载且服务端支持断点续传。
> 本机带宽约 45 KB/s，48MB 全量拉回并跑 `sha256sum -c` 未能在收口窗口内完成，
> 如实登记为下一轮 `G-06`，不记作通过也不记作失败。
> 同一 `scripts/package-release.sh` 在本地产出的 tarball 已完成解包 / doctor /
> 真实安装 / 真实拆卸全链路验证，MANIFEST 与 CI 产物同源。

---

## 5. 同步与边界

| 位置 | 首笔提交 sha |
|---|---|
| WSL 权威副本 `~/robot-autotest` | `d6016b13ab1a5b876e74ca5a62b0fcd30ba7dc88` |
| `origin`（公司群晖 NAS） | 同上 |
| `github` | 同上 |
| E 盘镜像 `E:\workspace\自动化测试\Robot-Autotest` | 同上 |

第二笔提交只回写 CI 结论与证据索引（**代码零改动**），最终四处 sha 以交付汇报为准；
本索引不写死最终 sha，避免每次回写都让文档过期。
GitHub 侧推送出现 `Bypassed rule violations: Changes must be made through a pull request` 提示，
属仓库分支保护的管理员放行，推送本身成功且 sha 一致。

**边界**：交付包只上传 Actions artifact，需登录 GitHub 才能下载；公开 Release 属对外口径，留大师拍板。
既有 `pr.yml` 依赖未注册的 self-hosted runner `rf-lab`，保持不动，属「待现场」。

---

## 6. 遗留

| 编号 | 内容 | 处置 |
|---|---|---|
| `L-39-03` | `-u user:pass` 命令脱敏 | **下一轮 P0**，`FR-v3.12-01` |
| `L-311-01` | 远程主机采集 | 下一轮 P1，`FR-v3.12-04` |
| `L-311-02` | 恢复记录当前 0 条 | 下一轮 P2 候选，真动作后取证 |
| `L-311-03` | 拓扑可视化画布 | 下一轮 P2 候选 |
| `L-311-04` | `SchemaIT` 口径漂移 | 下一轮 P1，`FR-v3.12-03` |
| `L-D01` | 公开 Release 未授权 | 待大师拍板，默认不创建 |

---

## 7. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-09 | 首版：证据清单、六项结论、机检读数、CI 与同步占位、遗留台账 |
| v1.1 | 2026-11-09 | CI 实测回写：run `42595970100` success / 1m49s / artifact 48,130,253 字节；补 `ci-run.json` 与 `ci-run.log`；登记三条 annotation 与 artifact 完整下载受网络限制的事实；同步四处首笔 sha |
