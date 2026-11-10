# EW42-03 远程主机只读采集（FR-v4.2-03）

> 结论：**代码侧判据全部通过；真实远端采集 `NOT-RUN`**（无目标机，见 `../EW42-00/`）。
> 本机采集与远程负向都真打了接口，不是单测自说自话。

## 1. 判了什么

| 层 | 读数 | 依据 |
|---|---|---|
| 单测（SSH 通道 + 采集器） | `Tests run: 18, Failures: 0, Errors: 0` | [unit-targeted.log](./unit-targeted.log) |
| 契约（真实 HTTP + 真库 + 真采本机） | 29 项 PASS、`fails=0` | [host-inventory-contract.log](./host-inventory-contract.log) |
| 远程负向（不带凭据 / 不带 confirm） | 两条均 400，且消息写明缺什么 | [remote-negative.json](./remote-negative.json) |
| 静态硬约束 | 18 条齐全 | `python3 tools/check_host_inventory.py`（门禁第 29 步） |

单测 18 例 = `SshHostCommandRunnerTest` 8 例 + `HostInventoryCollectorTest` 10 例。
SSH 的 8 例覆盖：拒绝提权、拒绝参数注入、指纹失败透出原因、超时杀进程、输出超限截断、
口令不进 argv、私钥临时文件用完即删、路由按凭据分通道。

本机真采结果为 `status=PARTIAL`、9/10 项有值：`GPU` 未采集到，原因写的是
`未采集到 GPU 信息：sh: 1: nvidia-smi: not found`。**这不是失败**——判据要求「没采到必须
有原因，不许填占位值」，契约第 ④ 项与第 ⑤ 项正是查这个。

## 2. 三道门（顺序不可换）

`HostInventoryService.accessOf` 依次判：

1. 本机（`localhost` / `127.0.0.1`）→ 直接走本机执行器，**不需要凭据、不需要确认**；
2. 远程无 `credentialId` → 400「远程主机采集需要传 credentialId（凭据库里的 SSH 口令或私钥）」；
3. 远程无 `confirm=true` → 400「远程主机采集需要二次确认：请求需带 confirm=true」；
4. 凭据解析走 `CREDENTIAL:USE`，落 `credential.use` 审计；账号为空再 400。

第 2、3 步在任何进程启动之前，所以 `remote-negative.json` 里的两条用例不依赖网络中可达性。

## 3. 代码侧硬约束（逐条都能被判红）

| 约束 | 位置 |
|---|---|
| `StrictHostKeyChecking=yes` + 显式 `UserKnownHostsFile` | `SshHostCommandRunner` |
| `BatchMode=yes`（不交互等待） | 同上 |
| `sudo` / `su` / `doas` 直接拒绝 | 同上（`PRIVILEGE` 正则 + 启动前判定） |
| 单条输出上限 256 KiB，超出标注「已截断」 | 同上（`MAX_OUTPUT_BYTES`） |
| 超时 `destroyForcibly` | 同上 |
| 口令只进 `SSHPASS`，不进 argv | 同上 |
| 私钥临时文件 0600、`finally` 删除 | 同上 |
| 路由只在「非本机 + 有凭据」时走 SSH | `HostCommandRunnerRouter` |
| 审计里的 host 掩码（IPv4 保留首尾段） | `HostInventoryService#maskHost` |
| 成功与失败都落审计 | `HostInventoryService#collect` |

## 4. 哪些判不了（NOT-RUN）

| 项 | 为什么 | 由什么补 |
|---|---|---|
| 真实远端 10 类采集 | 无目标机与已登记指纹 | `../EW42-00/` 的补齐方法 |
| 指纹首次登记 | 平台刻意不做 `ssh-keyscan` 自动信任 | 运维人工比对后写 `known_hosts` |
