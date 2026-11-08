# infra-monitor 交接文档（2026-10-30）

> 交接范围：Robot Framework 基础设施监控套件的调试进展与遗留问题。
> 主机：robot Framework（192.168.110.147，用户 evergreen）｜venv：`/home/evergreen/robot-test/.venv`（Python 3.12 + robotframework 7.5）

## 一、项目概况
- 位置：`~/robot-test/infra-monitor`（教学/研究性质，测试文件含大量学习要点注释）
- 结构：
  - `suites/`：01_connectivity ~ 05_resource_report，共 5 套件 26 用例
  - `resources/common.robot`：公共变量 + 用户关键字（在跳板机上下文中巡检内网）
  - `libraries/metrics_collector.py`：自研库（登记指标/导出指标/解析 Ping 输出/登记硬件指标）
  - `run_tests.sh`：一键运行；结果输出 `results/run_YYYYMMDD_HHMMSS/`
- 关键变量（common.robot）：GPU_SERVERS=10.202.54.101~104、STORAGE=10.202.54.105、R750=10.202.54.106~108、内网 IP 172.31.20.101~104（经跳板）、PING_COUNT=5、SSH_TIMEOUT=10s
- 凭据：明文存于 common.robot（${INSPUR_*}/${EHT_*）——建议后续迁入 Vault

## 二、当前状态（run_20261030_120849）
- 结果：**26 用例，4 通过 / 22 失败**，无 metrics.json 产出
- 通过项：磁盘挂载信息（Storage-01、R750 等直连主机）→ SSH 凭据、连接、采集逻辑均有效
- 失败项：全部为同一根因 —— `Imported library metrics_collector.py contains no keywords`，所有调用 登记指标/导出指标/解析 Ping 输出/登记硬件指标 的用例报 No keyword with name

## 三、根因（已定论，勿再怀疑 metrics_collector.py）
- venv 里的 robotframework 7.5 **安装损坏**：`robot/libdoc` 应为包目录（含 api.py/model/builder），实际是单个 `robot/libdoc.py` 文件
- 证据：`from robot.libdoc import LibraryDoc` → ImportError；`python -m robot.libdoc <库> list` 对任何库（含最小 ASCII 测试类）都输出空
- RF 7.x 运行期库关键字发现依赖 robot.libdoc 包 → 库发现恒为 0 → 全部库关键字不可用
- 库文件本身正常：import 成功，`dir(MetricsCollector)` 可见全部方法

## 四、修复步骤（待执行，预计 5 分钟）
1. `/home/evergreen/robot-test/.venv/bin/pip install --force-reinstall robotframework==7.5`
2. 验证：`python -m robot.libdoc libraries/metrics_collector.py list` 应列出 4 个关键字
3. （可选加固）用 `robot.api.deco.keyword` 显式命名；注意 `登记_硬件指标` 与套件调用 `登记硬件指标` 的下划线差异
4. 重跑 `bash run_tests.sh`，核对 results/ 与 metrics.json

## 五、备注
- run_tests.sh 与套件调用链没有问题，无需修改；只修 venv 安装
- 同组参考：RoboScope 部署日记、RIDE GUI 部署日记、infra-monitor 报告增强研究笔记
