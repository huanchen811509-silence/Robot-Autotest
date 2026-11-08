# infra-monitor 报告增强研究笔记（robot-test @ 192.168.110.147）

调研+整改日期: 2026-06。目标: 让 infra-monitor 套件的报告从"只有 pass/fail"变成"每条用例带具体数值 + 四种交付格式"。

## 一、现状诊断

项目位置 `~/robot-test/infra-monitor/`，4 个套件 + common.robot 共 320 行。

数据**已在采集**，但有三个短板:

| 短板 | 证据 | 后果 |
|---|---|---|
| ping 只测通断 | `ping -c N -W 2 > /dev/null && echo REACHABLE`，stdout 进了 /dev/null | 延迟、丢包率数据丢失 |
| 数值只进 Log | `Log  GPU-04 资源: 负载=... 内存=...%` | 数值埋在 log.html 深处，报告行看不到 |
| 无结构化存储 | 无 JSON/指标仓库 | 生成 Excel/Word/MD 无数据可用 |

环境事实:
- venv 已装 `openpyxl 3.1.5`、`python-docx 1.2.0`、`Jinja2 3.1.6` → Excel/Word/HTML 全可生成，psutil 非必需（远程机器走 SSH 解析）
- RoboScope(localhost:8145) API 能力强: `/reports/{id}/export`、`/reports/{id}/html`、`/reports/compare`、`/reports/tests/history`
- 测试目标 10.202.54.x 远程机器经 SSH，本机无 nvidia-smi → 指标采集走 SSH 命令行解析

## 二、目标架构: 采集 → 断言 → 登记 → 后处理 四层

```
第1层 采集关键字(改造 common.robot)   → 返回结构化数值, 数值写入测试消息
第2层 登记库(libraries/metrics_collector.py) → 登记指标 追加到 metrics.json
第3层 断言(套件显式)                  → 阈值判定, 失败消息带数值
第4层 后处理(tools/post_report.py)    → output.xml+metrics.json → HTML/Excel/Word/MD/CSV
```

产出效果（报告行示例）:

| 状态 | 用例 | 关键数值 |
|---|---|---|
| ✅ | GPU-04 ping 连通性 | 延迟 0.42ms / 丢包 0% / 阈值 <5ms |
| ✅ | GPU-04 GPU 状态 | 8×V100 利用率 3% / 温度 38C / 显存 1.2/32GB |

## 三、与 RoboScope 的关系

互补不冲突: RoboScope 管平台视角(执行/历史/对比)，post_report 管交付格式(Excel/Word/MD)。用例消息带数值后 RoboScope 界面每条用例直接可见数值。

## 四、关键设计点(整改时遵守)

1. **Set Test Message append=True**: 关键数值写入测试消息，RF 原生 report.html 与 RoboScope 都能看到
2. **metrics.json 结构**: `[{suite, test, target, metric, value, unit, threshold, verdict, ts}]`，每次 run 独立文件，支持多轮趋势对比
3. **ping 解析**: `rtt min/avg/max/mdev` 行 + `packet loss` 行，正则提取；丢包 100% 时延迟为 null
4. **采集与断言分离**: 采集关键字只负责取数+登记，断言仍由套件显式做，阈值可参数化
5. **不动登录凭据与服务器清单**，common.robot 现有变量原样保留

## 五、整改实施清单

| # | 动作 | 状态 |
|---|---|---|
| 1 | tools/post_report.py (284行, py_compile 通过) | ✅ 已完成 |
| 2 | libraries/metrics_collector.py 登记库 | ⏳ 待做 |
| 3 | common.robot 改造(新增采集关键字) | ⏳ 待做 |
| 4 | 4 个套件改用新关键字+登记指标 | ⏳ 待做 |
| 5 | 跑通演示生成 HTML/Excel/Word/MD | ⏳ 待做 |
