# v5.15.0 W8 · SSH 安全细节核对（只读）

> 依据 `tools/check_host_inventory.py` D 段（FR-v4.2-03 远程采集硬约束）；
> 本波只核对与留痕，不改产品代码。命中数为源码文件里出现该标记的文件数。

| # | 核对项 | 关键字 | 命中文件数 | 命中位置 |
|---|---|---|---|---|
| 1 | StrictHostKeyChecking=yes | `StrictHostKeyChecking=yes` | 1 | SshHostCommandRunner.java |
| 2 | BatchMode=yes | `BatchMode=yes` | 1 | SshHostCommandRunner.java |
| 3 | 拒绝提权 sudo / su / doas | `sudo / doas` | 1 | SshHostCommandRunner.java |
| 4 | 输出上限 | `MAX_OUTPUT / maxOutput / outputLimit` | 1 | SshHostCommandRunner.java |
| 5 | 采集超时 | `TIMEOUT / timeout` | 7 | BmcRedfishCollector.java, HostCommandRunner.java, HostCommandRunnerRouter.java, HostInventoryCollector.java, LocalHostCommandRunner.java |
| 6 | 口令不进 argv（sshpass -e 环境变量） | `sshpass / SSHPASS / -e` | 2 | LocalHostCommandRunner.java, SshHostCommandRunner.java |
| 7 | 远程二次确认 | `confirm / confirmed` | 1 | HostInventoryService.java |
| 8 | 凭据走 CREDENTIAL:USE | `CREDENTIAL / resolveMaterial` | 4 | BmcRedfishCollector.java, HostAccess.java, HostInventoryService.java, SshHostCommandRunner.java |
| 9 | 审计主机掩码 | `maskHost` | 1 | HostInventoryService.java |
| 10 | 本机白名单 | `isLocal / 127.0.0.1` | 3 | HostCommandRunnerRouter.java, HostInventoryService.java, LocalHostCommandRunner.java |

## 常规机检（退出码 1）

```text
HOST_INVENTORY FAIL
  - OS 接口命令与源码不一致（接口报的和真跑的不是同一条）
      接口: grep -m1 '^PRETTY_NAME=' /etc/os-release
      源码: export LC_ALL=C; grep -m1 '^PRETTY_NAME=' /etc/os-release
  - KERNEL 接口命令与源码不一致（接口报的和真跑的不是同一条）
      接口: uname -r
      源码: export LC_ALL=C; uname -r
  - CPU 接口命令与源码不一致（接口报的和真跑的不是同一条）
      接口: nproc; lscpu | grep -m1 'Model name'
      源码: export LC_ALL=C; nproc; lscpu | grep -m1 'Model name'
  - MEMORY 接口命令与源码不一致（接口报的和真跑的不是同一条）
      接口: free -m | head -2
      源码: export LC_ALL=C; free -m | head -2
  - DISK 接口命令与源码不一致（接口报的和真跑的不是同一条）
      接口: df -P / | tail -1; echo ---; lsblk -d -o NAME,SIZE,ROTA 2>/dev/null
      源码: export LC_ALL=C; df -P / | tail -1; echo ---; lsblk -d -o NAME,SIZE,ROTA 2>/dev/null
  - GPU 接口命令与源码不一致（接口报的和真跑的不是同一条）
      接口: nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader
      源码: export LC_ALL=C; nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader
  - NIC 接口命令与源码不一致（接口报的和真跑的不是同一条）
      接口: ip -br link; for i in /sys/class/net/*; do printf '%s %s\n' "$(basename $i)" "$(cat $i/speed 2>/dev/null)"; done
      源码: export LC_ALL=C; ip -br link; for i in /sys/class/net/*; do printf '%s %s\n' "$(basename $i)" "$(cat $i/speed 2>/dev/null)"; done
  - IP 接口命令与源码不一致（接口报的和真跑的不是同一条）
      接口: ip -br addr
      源码: export LC_ALL=C; ip -br addr
  - ROCE 接口命令与源码不一致（接口报的和真跑的不是同一条）
      接口: for p in /sys/class/infiniband/*/ports/*; do printf '%s|%s|%s\n' "$p" "$(cat $p/state 2>/dev/null)" "$(cat $p/rate 2>/dev/null)"; done
      源码: export LC_ALL=C; for p in /sys/class/infiniband/*/ports/*; do printf '%s|%s|%s\n' "$p" "$(cat $p/state 2>/dev/null)" "$(cat $p/rate 2>/dev/null)"; done
  - ROUTE 接口命令与源码不一致（接口报的和真跑的不是同一条）
      接口: ip route show
      源码: export LC_ALL=C; ip route show
  - OS 记录的命令与源码白名单不一致（证据漂移）
  - KERNEL 记录的命令与源码白名单不一致（证据漂移）
  - CPU 记录的命令与源码白名单不一致（证据漂移）
  - MEMORY 记录的命令与源码白名单不一致（证据漂移）
  - DISK 记录的命令与源码白名单不一致（证据漂移）
  - GPU 记录的命令与源码白名单不一致（证据漂移）
  - NIC 记录的命令与源码白名单不一致（证据漂移）
  - IP 记录的命令与源码白名单不一致（证据漂移）
  - ROCE 记录的命令与源码白名单不一致（证据漂移）
  - ROUTE 记录的命令与源码白名单不一致（证据漂移）
```

## 负向自证（退出码 0）

```text
SELFCHECK 干净数据（10 类，GPU 未采到但给了原因）: PASS（符合预期）
  SELFCHECK 10 类全采到（status=OK）: PASS（符合预期）
  SELFCHECK 少一类（9 类）: RED（符合预期）
  SELFCHECK 顺序错（GPU 与 DISK 对调）: RED（符合预期）
  SELFCHECK 未采集却没有原因: RED（符合预期）
  SELFCHECK 用 '-' 冒充采集结果: RED（符合预期）
  SELFCHECK status 与项数不符: RED（符合预期）
  SELFCHECK 记录的命令与源码不符: RED（符合预期）
  SELFCHECK 缺 collectedAt: RED（符合预期）
  SELFCHECK 有值却又带 reason（语义矛盾）: RED（符合预期）
  SELFCHECK 只读命令 `ip -br addr`: PASS（符合预期）
  SELFCHECK 带 2>/dev/null 的只读命令: PASS（符合预期）
  SELFCHECK 重定向写文件: RED（符合预期）
  SELFCHECK rm -rf: RED（符合预期）
  SELFCHECK 就地改文件 sed -i: RED（符合预期）
  SELFCHECK 用 ip 改地址: RED（符合预期）
  SELFCHECK 装包: RED（符合预期）
  SELFCHECK 未登记的可执行名: RED（符合预期）
  SELFCHECK 接口命令与源码漂移: RED（符合预期）
  SELFCHECK 接口命令与源码一致: PASS（符合预期）
  SELFCHECK W42-03 远程采集硬约束（删一条即判红）: 9/9 条符合预期
  SELFCHECK W42-03 干净源码: PASS（符合预期）
SELFCHECK_RESULT FAILS=0
```

## 结论

远程采集九项硬约束由门禁 29（`check_host_inventory.py`）负向自证逐条把守，
本节 `SELFCHECK W42-03 远程采集硬约束` 为 9/9 条符合预期、干净源码 PASS，
故本波不改动产品代码。

**口径订正（如实登记，不放宽判据）**：初版核对把「口令不进 argv」写成
`askpass / ASKPASS` 关键字，实测命中 0；读 `SshHostCommandRunner.java:24,124-126`
后确认真实实现是 `sshpass -e`（口令走 `SSHPASS` 环境变量，不出现在 argv），
并非缺少防线。核对关键字已改为 `sshpass / SSHPASS / -e`，结论随之订正。

真机错误口令样本未构造（避免污染凭据库与真实设备），如实登记为 PARTIAL，
见 `05-TEST-REPORT.md` §5。
