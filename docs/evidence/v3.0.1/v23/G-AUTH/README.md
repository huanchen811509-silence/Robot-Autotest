# G-授权执行记录（TC-AI-11 真实本机 CLI 调用）

**授权** 大师 2026-11-08「全部授权」｜**执行** 学徒｜**执行时间** 2026-11-08 10:43–10:50（GMT+8）

> 结论先行：**TC-AI-11 由 BLOCKED 变为 PASS**，并顺带发现并修复 1 个真实缺陷
> （后端起 CLI 子进程后没关闭它的 stdin，真实 CLI 会阻塞到 60s 超时降级）。
> 修复前红、修复后绿，红/绿对照可复跑（§5）。

## 1. 授权范围与不越界

| 项 | 内容 |
|---|---|
| 被授权做什么 | 发起真实本机 CLI 调用（消耗已登录凭据与 token），记录模型名 / 耗时 / token 读数 |
| 不做什么 | 不在仓库里落任何凭据；不把 `FEATURE_AI_REAL_CLI` 长期打开；不改 AI 产品行为；不绕过本机安全策略 |
| 临时改动 | 服务级 drop-in 与 600 权限 env 文件**仅在一次验证期间存在**，脚本 `trap` 保证回滚（§7） |

## 2. 候选 CLI 探测（本机实况）

| CLI | 路径 | 状态 |
|---|---|---|
| `claude` | `/mnt/c/Users/everg/AppData/Roaming/npm/claude` | `--version` 可用（**2.1.267**），但真实调用**被安全策略阻断** |
| `codex` | `/home/evergreen/.local/bin/codex`（WSL 包装 → 原生 codex.exe） | **可用**，本次即用此候选 |
| `gemini` | — | 未安装 |
| `aider` | — | 未安装 |

`claude` 为什么不可用：其 npm shim 直接 `exec` 原生 `claude.exe`，会话启动时进程内会调 `reg.exe` 查询注册表，
命中本机「程序黑名单」。执行环境明确返回「不可批准、不可绕过、不要重试或换壳」——**按提示未做任何绕过**。
完整日志：`raw/cli-probe.log`。

## 3. 直连真实调用（不经后端）

```bash
~/.local/bin/codex exec --skip-git-repo-check "请只回复两个字：已连通" < /dev/null
```

| 读数 | 值 |
|---|---|
| 退出码 / 墙钟 | `rc=0` / **13 988 ms** |
| stdout | `已连通`（恰好 2 个汉字，符合指令） |
| 模型 | **`deepseek-v4-flash`** |
| provider | `volcengine-coding-plan` |
| 会话 id | `01a1b90a-e0b8-78e3-ac1b-e1f28d1a2045` |
| token | **20 555**（codex 自带 `tokens used` 统计行） |
| 沙箱 / 审批 | `read-only` / `never` |

## 4. 后端链路（修前：60s 超时降级）

`POST /api/ai/chat` `{"projectId":29,"sessionId":0,"prompt":"请只回复两个字：已连通"}`

```text
HTTP=200  wall_ms=61 739
answer      : 'CLI 不可用，已显式降级（超时，进程树已终止）'
degraded    : True
cli         : 'codex'
exitCode    : -1
durationMs  : 60 011
stderr      : 'Reading additional input from stdin...'
```

同一个 CLI、同一句提示，直连 14s 成功、走后端 60s 超时——差异就在 stdin。

> 该次响应文件已被后续成功运行覆盖；上表读数取自当时终端输出，逐字抄录。
> 同一缺陷另有可复跑的机器证据，见 §5 的 `stdin-fix-redgreen.log`。

## 5. 缺陷与修复

| 项 | 内容 |
|---|---|
| 缺陷 | `AiProcessManager.execute` 起子进程后从未关闭子进程的 stdin，非交互 CLI 若读 stdin 会一直阻塞到 `AI_CLI_TIMEOUT_SECONDS` |
| 为什么会漏 | 此前只用 `fixtures/ai/fake-cli.sh`，它不读 stdin，所以永远发现不了；只有真实 CLI 才会走到这条路径 |
| 修复 | 起进程后立即 `process.getOutputStream().close()`，给子进程 EOF（等价于 shell 里 `< /dev/null`），跨平台、不依赖 `/dev/null` 文件 |
| 回归夹具 | `fixtures/ai/stdin-eof-probe.sh`：读 stdin 到 EOF 才输出 `STDIN_EOF_OK` |
| 回归用例 | `backend/src/test/java/.../AiProcessManagerStdinTest.java` |

红/绿对照（`raw/stdin-fix-redgreen.log`，脚本做法：备份 → 摘掉修复 → 跑 → 还原 → 再跑）：

```text
修前（去掉 closeChildStdin）：Tests run: 1, Failures: 1 —— Time elapsed: 11.08 s
  Expecting value to be false but was true   ← timedOut 为 true，说明确实阻塞在 stdin
修后（恢复 closeChildStdin）：Tests run: 1, Failures: 0 —— Time elapsed: 0.337 s
```

耗时从 11.08s 掉到 0.337s 本身就是判据：修前是「等超时」，修后是「立刻拿到 EOF」。

## 6. 后端链路（修后：真实模型回答）

重建 jar 并重启（**源码对齐 ≠ 部署对齐**，v2.2 踩过；本次 `mvn -o -B package -DskipTests` + `systemctl --user restart`）后复跑：

| 读数 | 值 |
|---|---|
| HTTP / 墙钟 | `200` / **15 744 ms** |
| `answer` | `已连通` |
| `degraded` | **False** |
| `exitCode` | **0** |
| `cli` | `codex` |
| `durationMs` | **15 626** |
| `sessionId` | `2` |
| `audited` | True |

原始响应：`raw/chat.json`；过程日志：`raw/backend-chain.log`。

## 7. 环境处理与回滚（都已复原）

| 项 | 做法 | 结果 |
|---|---|---|
| 服务级开关 | 临时 drop-in `40-ai-real-cli.conf`（`FEATURE_AI_REAL_CLI=true`、`AI_CLI_PATH=~/.local/bin/codex`） | 验证后**已删除**，残留数 **0** |
| 凭据 | `~/.config/robot-autotest/ai.env`，权限 **600**，仅 1 行，不打印不落日志 | 验证后**已删除** |
| 产品开关 | `ai.enabled` 置 `true` 跑一次 | **已还原 false**（PUT 200 复核） |
| 服务健康 | 重启后就绪探针 | `:8081` 返回 401（缺 token，属正常） |
| 仓库 | 不含任何凭据；`ai.env` 在 `~/.config` 下，不在版本库 | 已确认 |

**踩到一个探针坑（记下来）**：就绪探针不能用 `:8001/_ping`——它是 node 入口自己回的 200，后端没起来也照样 200；
必须打 `:8081`（后端直连）。本轮第一次因此把 502 误判成「后端已就绪」。

## 8. 这次没有覆盖到的

| 项 | 状态 | 说明 |
|---|---|---|
| `claude` 分支的真实调用 | **未验** | 被 `reg.exe` 程序黑名单阻断，不可绕过；解除需在「安全中心 → 命令安全 → 程序黑名单」放行，或换机器 |
| `gemini` / `aider` 分支 | 未验 | 本机未安装 |
| token 统计的链路内读数 | **部分** | 20 555 是直连那次 codex 自报；后端只回 `durationMs`，不解析 token（产品未做该字段） |
| 多轮会话一致性 | 未验 | 只跑了单轮问句 |

## 9. 复跑命令

```bash
# 0) 真实 CLI 是否可跑（不消耗 token）
~/.local/bin/codex --version

# 1) 直连真实调用（消耗 token）
~/.local/bin/codex exec --skip-git-repo-check "请只回复两个字：已连通" < /dev/null

# 2) 缺陷回归（不消耗 token，走夹具）
cd backend && mvn -o -B test -Dtest=AiProcessManagerStdinTest

# 3) 后端链路（需临时 drop-in + ARK_API_KEY 透传，脚本自带回滚）
#    见 raw/backend-chain.log 头部记录的 drop-in 内容；跑完务必确认：
ls ~/.config/systemd/user/robot-autotest-backend.service.d/ | grep -c 40-ai-real-cli   # 期望 0
```
