# v5.7.0 发版 / 部署 / 同步证据（2026-11-14 19:40）

> 触发：大师指令「更新关于 版本迭代 发版 部署 然后同步到公司群晖 Github」。
> 本目录只放**命令原文与读数**，结论写进 `docs/release/v5.7.0/RELEASE-NOTES.md` 与
> `docs/release/v5.7.0/05-TEST-REPORT.md` §七；**发版不等于判 G 门**，G 门仍未判定。

## 一、做了什么（可复跑）

| # | 动作 | 命令 | 读数 |
|---|---|---|---|
| 1 | 版本口径回写 | `CHANGELOG.md` 首条改「落号与发版说明」 | 首条 = `v5.7.0`，带 `<!-- current -->` |
| 2 | 构建信息与静态副本 | `python3 scripts/gen_build_info.py` + `cp CHANGELOG.md frontend/public/` | 由 `deploy.sh` 第 1/5 步完成 |
| 3 | 构建 + 部署 + 自校验 | `bash scripts/deploy.sh` | 退出码 0，四项判据全 PASS |
| 4 | 部署自校验复跑 | `bash scripts/deploy.sh --verify` | `PASS=4 FAIL=0 SKIP=0` |
| 5 | 版本一致性（含运行期） | `python3 tools/check_version_consistency.py` | `VERSION_CONSISTENCY PASS (0 项)` |
| 6 | 数据库体检 | `bash scripts/db-audit.sh` | `db-audit: PASS` |
| 7 | 交付包 | `bash scripts/package-release.sh` | `PACKAGE PASS`，423 文件 |
| 8 | 55 步全量门禁复跑（发版提交上） | `bash scripts/gates.sh` | `GATES_RESULT FAILS=0 UNJUDGED=0` |
| 9 | 双远程同步 | `git push origin main` / `git push github main` / 标签 | 见 §三 |

## 二、关键读数

| 项 | 值 | 来源 |
|---|---|---|
| 版本 | `5.7.0` | `VERSION` / `/api/_ping` / 入口托管 `CHANGELOG.md` 三处一致 |
| 收口运行 | 见 §六 | 在发布提交上再跑一次 `deploy.sh`，判据 1/4 恢复「jar commit == HEAD」 |
| 构建 commit | `762d2dd`（归档运行） | jar 内 `build-info.properties` 与当时的 HEAD 一致（判据 1/4） |
| 进程重启 | 起于 `11-14 19:38:28`，晚于 jar | 判据 2/4 |
| 双端口健康 | `8081 /api/_ping=200`、`8001 /healthz=200` | 判据 3/4 |
| 运行期 | `version=5.7.0`、`commit=762d2dd` | 判据 4/4 |
| 业务表 | **108** | `information_schema` 统计（不含 `flyway%`） |
| 最新迁移 | **11.64** | `flyway_schema_history ORDER BY installed_rank DESC` |
| 交付包 | `robot-autotest-5.7.0-762d2dd-linux-x64.tar.gz` | `releases/`（已 gitignore，不入库） |
| 包大小 | `49,250,159` B（解包 `59,012,910` B / 423 文件） | `MANIFEST.json` |
| 包 sha256 | `42147aff152e41f88500711e5f3014d3d7a797b6acfe7b2ce3328aed034b8203` | `.sha256` 边车文件 |

## 三、同步判据

| 位置 | 期望 | 判据 |
|---|---|---|
| WSL 权威副本 `~/robot-autotest` | HEAD = 发版提交 | `git rev-parse HEAD` |
| 群晖 `origin`（`ssh://synology-robot/volume1/git/repos/robot-autotest.git`） | `refs/heads/main` = HEAD | `git ls-remote origin main` |
| GitHub `github`（`huanchen811509-silence/Robot-Autotest`） | `refs/heads/main` = HEAD | `git ls-remote github main` |
| 标签 | `refs/tags/v5.7.0` 在两端可见 | `git ls-remote --tags` |
| E 盘文档工作区 `Robot-Autotest` | HEAD = 同一次提交 | `git merge --ff-only FETCH_HEAD` 后 `rev-parse HEAD` |

**三处 sha 不一致即判同步失败**，不得只凭 push 回显宣告成功。

## 四、本目录文件清单

| 文件 | 内容 |
|---|---|
| `raw/deploy.log` | `deploy.sh` 全量：构建 → 停旧进程 → 起新进程 → 健康检查 → 四项判据 |
| `raw/deploy-verify.log` | `deploy.sh --verify` 独立复跑 |
| `raw/package-release.log` | 交付包打包全量输出（MANIFEST / tarball / sha256 / 解包自检） |
| `raw/version-consistency.log` | 五处版本同源机检（含运行期与入口托管） |
| `raw/gates-55.log` | 55 步全量门禁在发版提交上的复跑输出（含 53~55 `--self-test` 真注入段） |

## 五、两次构建运行（为什么证据里的 commit 会比 HEAD 旧一个提交）

`deploy.sh --verify` 的判据 1/4 是「jar 内 build commit == HEAD」。而**记录构建读数的文件自身
还要再提交一次**，提交后 HEAD 立刻前移，于是「已归档的日志」永远比 HEAD 慢一个提交。
处理办法沿用 `fac4512` 定下的口径：

| 运行 | 时点 | 用途 | 落点 |
|---|---|---|---|
| 归档运行 | 发版文档提交之后立即执行 | 产出本目录 `raw/*.log`，作为**可复核的原始读数** | 入库 |
| 收口运行 | 本次提交全部落盘之后再执行一次 | 让 HEAD 上 `deploy.sh --verify` 恢复四项全 PASS | 只报读数，不新增日志文件（避免再次前移 HEAD） |

两轮之间**只有文档差异**，可用 `git diff --stat <归档运行 commit>..HEAD` 自查（应只有 `.md` / `.log`）。

## 六、口径提醒

- 本轮补做的是**发版与部署**，不是 G 门判定；`05-TEST-REPORT.md` §四的 153 条 UI / 真机 / 性能 TC 仍未执行。
- 交付包**不含**任何凭据、日志、`node_modules`、`target`、`.git`；打包脚本内已有四条断言。
- 旧的同版本包（`fac4512` / `ef12e04` / `0060216`）已移出 `releases/`，避免门禁第 30 步按文件名排序取到过期包。
