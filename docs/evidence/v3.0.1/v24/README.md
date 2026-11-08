# v2.4 · CLI 多 agent 接入与内置兜底 · 证据索引

> 授权：大师 2026-11-08「CLI 要增加 Hermes agent、deepseek harness TUI，保底在软件包里集成一个 pi agent」。
> 关联文档：`docs/plan/v2.4-{CLI多agent-评估增补,PRD-增补草案,DEV-PLAN-增补草案,TEST-PLAN-增补草案}-20261108.md`

## 1. 结论一句话

三条候选里 **hermes 与内置 pi 已端到端跑通**（真实 HTTP + 真实模型），
**dsh 集成就绪但真实推理缺 DeepSeek 官方 key**；
并且在本批修掉一个只有真实链路才暴露的缺陷：内置 pi 的 node 依赖不自解析，导致「兜底」在收窄 PATH 下直接失效。

## 2. 证据清单

| 编号 | 内容 | 路径 | 关键读数 |
|---|---|---|---|
| CLI-01 | 三条候选可用性矩阵 | `CLI-01/raw/cli-probe.log` | hermes rc=0 / dsh rc=1 缺凭据 / pi rc=0（PATH 不含 node） |
| CLI-02 | 后端真实链路两场景 | `CLI-02/raw/backend-chain.log` | A: `cli=hermes` 4858ms；B: `cli=pi` 1697ms |
| CLI-02 | 链路响应原文 | `CLI-02/raw/chat-hermes.json`、`chat-pi-bundled.json` | `degraded=false`、`exitCode=0` |
| CLI-03 | 内置 agent 安装与启动器 | `CLI-03/raw/bundled-agent.log` | wrapper rc=0；裸 npm bin rc=127；体积 158M |

## 3. 场景 A/B 的设计意图

| 场景 | 环境 | 期望 | 实测 |
|---|---|---|---|
| A | 默认 PATH（`~/.local/bin` 可见） | 选第一条可用候选 | `cli=hermes`、`answer=已连通` |
| B | PATH 收窄为 `/usr/local/bin:/usr/bin:/bin` | 只剩内置 pi，仍要出答案 | `cli=pi`、`answer=已连通` |

场景 B 是「保底」的唯一严格证明：把 hermes / dsh / claude / codex / gemini / aider 全部变成不可见，
`/api/ai/state` 里只有 `pi: true`，链路依然 `degraded=false`。
若只做场景 A，无法区分「兜底真的有效」和「刚好有外部 CLI」。

## 4. 本批修掉的缺陷

**现象**：场景 B 首次运行返回 `degraded=true`、`exitCode=127`、`/usr/bin/env: 'node': No such file or directory`。

**根因**：内置副本的可执行文件是 npm 生成的软链，shebang 为 `#!/usr/bin/env node`，
只认调用方 PATH 上的 node；后端 systemd 的 PATH 是
`/home/evergreen/.local/bin:/usr/local/bin:/usr/bin:/bin`，不含 node（node 在 `/opt/node24/bin`）。

**修法**：安装脚本额外生成 `bin/pi` wrapper（纯 sh，自己找 node 再 exec），后端解析优先用 wrapper。

**回归**：同一收窄 PATH 下 wrapper rc=0、裸 bin rc=127（`CLI-03/raw/bundled-agent.log` 第 3 节）。
这是「修前红、修后绿」的直接对照，不是推测。

## 5. 复跑方式

```bash
# 1. 候选矩阵（真实调用各一次，约 20s）
WSLENV=ARK_API_KEY wsl.exe -d Ubuntu-24.04 bash -lc 'bash /tmp/cli_evidence2.sh < /dev/null'

# 2. 后端两场景（临时改 drop-in，结束时自动回滚，约 60s）
WSLENV=ARK_API_KEY wsl.exe -d Ubuntu-24.04 bash -lc 'bash /tmp/cli_chain.sh < /dev/null'

# 3. 安装器幂等（不联网）
bash tools/ai/ensure-pi-agent.sh --check

# 4. 单测
cd backend && mvn -o -B test -Dtest="Ai*Test"
```

脚本 `cli_evidence2.sh` / `cli_chain.sh` 的正文见本目录 `raw/` 下的同批日志首部注释；
若已清理 `/tmp`，按上述四条命令重跑即可复现，不依赖脚本残留。

## 6. 门禁状态（当轮 2026-11-08 11:32）

```text
18 PASS / 1 FAIL / GATES_RESULT FAILS=1
13-browser_regression -> FAIL 浏览器回归无法完成: fetch failed
后端 1073 tests / 前端 53 files·370 tests / 编辑器 24 files·219 tests
```

第 13 步失败与 v2.3 遗留同因：Chrome CDP `:9222` 无监听，该后台任务已被大师取消且未重启。
**本批未把这一步算作通过**，也不因本批改动而新增失败。

## 7. 未验与遗留

| 编号 | 项 | 原因 | 补齐方式 |
|---|---|---|---|
| L-CLI-01 | dsh 真实推理 | 需 DeepSeek 官方 key；ARK key 判 401 | 提供 key 后跑一次 `dsh headless` |
| L-CLI-02 | 空目录首次安装 pi | 已装，重装需再下载 158 MB | `ensure-pi-agent.sh --dir /tmp/pi-test` |
| L-CLI-03 | `dsh tui` profile | `~/.dsh/profiles` 只有 `headless`/`web` | 人工 `dsh tui --from-default-profile tui` |
| L-CLI-04 | pi 版本漂移 | developer preview | 升级时同步 `PI_VERSION` 与 argv 断言 |

## 8. 安全与凭据

- 所有日志中 key 一律 `<redacted>` 或只记长度，未出现明文。
- 临时 `ai.env`（600）在脚本退出时删除；`ai.enabled` 还原；drop-in 只删本批新建的 `ai-real.conf`。
- 未替用户写入任何 DeepSeek / ARK 凭据到仓库或数据库。
