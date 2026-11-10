# EW42-00 SSH 目标与凭据方案准入（FR-v4.2-03）

> 结论：**代码侧准入完成，真机目标 `NOT-RUN`**。没有可用的非生产 SSH 目标与已登记的
> `known_hosts` 指纹，本轮不发起任何真实 SSH 握手；判据里「真实远端采集成功」这一项记
> `NOT-RUN`，不得用单测或本机采集冒充。

## 1. 判了什么

| 项 | 结论 | 依据 |
|---|---|---|
| 凭据存储 | 复用既有 AES-256-GCM 凭据表，不新增密钥体系 | `CredentialService` + `SecretCipher` |
| 凭据授权 | 取用要求 `CREDENTIAL:USE`，落 `credential.use` 审计 | `CredentialService#resolveMaterial` |
| 主机指纹 | `StrictHostKeyChecking=yes` + 显式 `UserKnownHostsFile`，未知主机一律拒绝 | `SshHostCommandRunner` |
| 指纹失败可解释 | 报「指纹校验失败（StrictHostKeyChecking=yes，known_hosts=…）」并附原输出首行 | 单测 `指纹失败要透出原因` |
| 非交互 | `BatchMode=yes`，凭据缺失直接失败，不等输入 | `SshHostCommandRunner` |
| 口令不落 argv | 口令只进 `SSHPASS` 环境变量，argv 里只有 `sshpass -e ssh …` | 单测 `口令不进argv` |
| 私钥生命周期 | 临时文件 0600，`finally` 删除 | 单测 `私钥临时文件用完即删` |
| 提权拒绝 | 命令命中 `sudo` / `su` / `doas` 直接拒绝且不启动进程 | 单测 `拒绝提权命令` |
| 参数注入 | host / 账号走白名单正则，`-oProxyCommand=…` 判非法 | 单测 `拒绝参数注入` |

以上 9 项里，除「主机指纹」的**真实握手**需要远端配合外，其余 8 项已由
`SshHostCommandRunnerTest` 在假 ssh/sshpass 下证完（8/8 PASS，日志见 `../EW42-03/`）。

## 2. 哪些判不了（NOT-RUN）

| 项 | 为什么 | 由什么补 |
|---|---|---|
| 真实 SSH 握手成功 | 未提供非生产目标机；也不允许拿生产机试 | 大师提供目标 + 指纹登记后再跑 |
| 首次指纹登记流程 | 平台侧**不做** `ssh-keyscan` 自动信任（那等于关掉校验） | 运维在目标机旁执行 `ssh-keyscan`，人工比对后写入 `known_hosts` |
| 真实 10 类采集输出 | 同上；本机采集只能证明解析器 | 目标机就绪后补一次 `POST /collect` 真机证据 |

## 3. 准入结论

1. 平台不接受「先连上再说」：指纹不在 `known_hosts` 里，连接失败是**预期行为**。
2. 远程采集必须同时带 `credentialId` 与 `confirm=true`；缺一项在连远端之前就 400
   （负向实证见 `../EW42-03/remote-negative.json`）。
3. 真实远端采集在目标机就绪前保持 `NOT-RUN`，`W42-05` 的门禁不因此放行任何一项。
