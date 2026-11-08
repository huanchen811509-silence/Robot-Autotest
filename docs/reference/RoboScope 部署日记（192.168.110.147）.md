# RoboScope 部署日记（192.168.110.147）

**日期**: 2026-10-30 ｜ **执行**: Catty Agent（Netcatty 自动化）

## 一、最终成果

| 服务                    | 入口                                      | 状态                          |
| --------------------- | --------------------------------------- | --------------------------- |
| **RoboScope v0.16.1** | http://192.168.110.147:8145             | ✅ 运行中，**界面已切换为中文**（原生支持多语言） |
| RoboScope API 文档      | http://192.168.110.147:8145/api/v1/docs | ✅ 200                       |
| RF Dashboard 2.5.0    | http://192.168.110.147:8080             | ✅ 运行中（3 个轮次量化看板）            |
| Robot Framework       | 7.5 + seleniumlibrary 6.9.0             | ✅ 位于 \~/robot-test/.venv    |

* RoboScope 默认登录: [`admin@roboscope.local`](mailto:admin@roboscope.local) / `admin123`（**首次登录后请修改密码**）
* 安装路径: `/home/evergreen/roboscope-offline-linux`（独立 .venv，不影响其他环境）

## 二、部署过程与问题记录

### 1. 下载（286.5 MB，最大的坑）

* GitHub 官方源极慢且频繁中断（实测 15-60 KB/s，预计 5 小时+）
* 测速对比 4 个源后改用 **gh-proxy.com 镜像**（约 1.4 MB/s）+ `curl -C -` 断点续传，约 10 分钟完成
* **事故**: 第一次下载时，早前超时中断的 curl 进程未被清理，与后台任务**双写同一文件**，文件膨胀至 320 MB（官方 300 MB），SHA256 不匹配 → 删除重下
* **教训**:
  * 后台任务启动前务必 `pgrep` 确认旧进程已清理
  * `pkill -f` 模式会匹配自身命令行导致脚本自杀，应改用 `pkill -x curl`（精确进程名）
  * 下载后必须校验官方 SHA256（`fee80800...bb307d2` ✓）

### 2. 安装

* 官方离线包打包疏漏: wheels/ 缺 `secretstorage>=3.2` 和 `jeepney`（keyring 25.7.0 依赖链）→ 从 PyPI 补下载两个 wheel 放入 wheels/ 后安装成功
* 半成品 .venv 报错 → `UV_VENV_CLEAR=1 bash install-mac-and-linux.sh` 重建

### 3. 语言

* RoboScope **原生支持中文**：界面 Settings 内置多语言切换，已切换为中文，无需任何改造

## 三、运维速查

```bash
# 启动
cd /home/evergreen/roboscope-offline-linux && nohup bash start-mac-and-linux.sh > roboscope.log 2>&1 &
# 停止
bash /home/evergreen/roboscope-offline-linux/stop-mac-and-linux.sh
# 日志
tail -f /home/evergreen/roboscope-offline-linux/roboscope.log
# 端口/主机配置
vi /home/evergreen/roboscope-offline-linux/.env   # HOST=0.0.0.0, PORT=8145
```

## 四、遗留事项

* [ ] 首次登录后修改默认密码
* [ ] Docker 未安装（有 podman 4.9.3），容器运行时特性需另行配置
* [ ] 服务为 nohup 方式，重启后需手动启动（可配 systemd 用户服务实现开机自启）
* [ ] Playwright 浏览器二进制未预装，GUI 执行浏览器用例时按需安装

详细版日记见服务器: `/home/evergreen/roboscope-offline-linux/DEPLOY_LOG.md`