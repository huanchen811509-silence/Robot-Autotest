# W5 运维与测试体系证据（v5.6.0 / W56-51~55）

测量日期：2026-11-14（GMT+8）
入口：`http://127.0.0.1:8001`（后端 `:8081`，WSL systemd --user 托管）

## 一、W56-51 部署自校验 `deploy.sh --verify`

四项判据：构建产物 commit == HEAD / 运行进程晚于 jar / 健康 / 运行期 version 与 commit。

| 场景 | 结果 | 证据 |
|---|---|---|
| 只重建不重启（新 jar + 旧进程） | 判据 2、4 红；判据 3 仍是 200 | `raw/deploy-verify-不重启判红.log` |
| 重启后再验 | 4/4 PASS，退出码 0 | `raw/deploy-verify-四判据通过.log` |

关键点：进程活着、接口 200 都不能当部署成功的证据——jar 换了没重启，行为还是旧的。
第一次实测时 `--verify` 就在这种状态下判红（`FAIL 2/4 重启：进程起于 00:56:06，早于 jar 的 00:58:19`）。

## 二、W56-52 性能门禁不再假绿

`tools/check_perf_budget.py` 缺采样时的口径由「SKIP 退出 0」改为 **退出 3 = UNJUDGED（未判）**；
门禁总表 `scripts/gates.sh` 把 3 单列为「未判」，同时计入非绿项，末尾输出
`GATES_RESULT FAILS=n UNJUDGED=m`。

| 情形 | 退出码 | 输出 |
|---|---|---|
| 有报告且未越线 | 0 | `PERF_RESULT PASS` |
| 删掉采样文件 | 3 | `PERF_RESULT UNJUDGED(没有采样，未判；不算通过)` |
| `--require` + 无采样 | 1 | `[FAIL] 未找到性能报告…` |
| `--self-test` | 0 | 9 条自检，含「无采样必须未判」两条 |

## 三、W56-53 效果类指标（三项，各一份带日期文件）

| 指标 | 结果 | 文件 |
|---|---|---|
| Top8 主页面首屏成功率 | 100%（160 次采样，8 个页面 × 20 次） | `../effect-metrics/top8-success-20261114.json` |
| 创建工程 P95 | 44.5ms（20 个样本，创建后即删） | `../effect-metrics/create-p95-20261114.json` |
| 10000 行长列表 minFps | **未测**（本机列表 20 行/页，实测 60.9fps 不代表目标场景） | `../effect-metrics/longlist-fps-20261114.json` |

口径：没有样本就写「未测」，不填 0 冒充达标；fps 的观测值单列在 `observed`，不进结论。

## 四、W56-55 UI 冒烟 E2E

`node scripts/e2e-smoke.mjs`：真实 Chrome（CDP 9222）走
登录 → 进工程 → pytest 工作台发起 fake → 打开报告。10 条断言全 PASS，
发起的那次执行 7.4s 完成、1 条用例通过。

证据：`../effect-metrics/e2e-smoke-20261114.json`。

E2E 抓到两个真缺陷：

1. `/pytest-workbench` **整页空白**，控制台 `SyntaxError: 10`。根因是文案 `hostname@ip`
   里的 `@` 被 vue-i18n 当成链接消息语法，编译失败导致组件渲染中断——组件单测与门禁全绿，
   只有真实浏览器能发现。修法是写成 `{'@'}`（整串改用双引号）。
2. **执行成功但报告空白**（详见 `../w3-report/README.md` §三）。这个缺陷第一次跑 E2E 时
   没被抓到，因为当时 `e2e-09` 只断言「页面有『用例』字样、不报不存在」——空白报告页也满足。
   断言已加强为「报告页必须出现用例编号」，加强后立刻判红，修完再跑转绿。

## 五、W56-54 契约快照

`python3 tools/gen_api_contract.py` 重生成后 `--check` 一致：410 endpoints / 341 paths，
增量来自本版新增的 `/precheck`、`/runs/async`、`/runs/{id}/progress`、`/runs/{id}/cancel`。

## 六、29 号机检的「证据过期」与重采

全量门禁里 `29 host_inventory` 曾判红：10 类采集命令全部报「接口命令与源码不一致」。
取证结论是**证据过期，不是运行期缺陷**——`docs/evidence/v3.11.0/EW311-01/host-inventory.json`
产出于 2026-11-09，而 v4.12.0 W-D 才给 10 类命令统一加 `export LC_ALL=C;` 前缀；
现采记录的 10 类命令全部带该前缀，与源码逐字一致（`raw/host-inventory-command-drift.json`，`same:false` × 10 类）。

处理方式不是改证据凑源码，而是**按门禁自身的设计重采一次**：`gates.sh` 的
`HI_EVID` 取 `docs/evidence/v${VERSION}/EW*`（VERSION=5.0.0），该目录此前不存在才回落到
v3.11.0 冻结快照。补跑 `OUT_DIR=docs/evidence/v5.0.0/EW500-HI bash scripts/host-inventory-contract.sh`
后 `RESULT PASS fails=0`（本机真实采集 9/10 项有值，GPU 未采集到并写明原因
`nvidia-smi: not found`），29 号随之转绿。

## 七、复现命令

```bash
bash scripts/deploy.sh --verify
python3 tools/check_perf_budget.py --self-test
python3 tools/effect_metrics.py --samples 20
E2E_EVIDENCE=docs/evidence/v5.6.0/effect-metrics/e2e-smoke-$(date +%Y%m%d).json \
  node scripts/e2e-smoke.mjs --shot-dir /tmp/e2e-shots
```
