# RIDE（Robot Framework IDE）部署日记（192.168.110.147）

**日期**: 2026-10-30 ｜ **执行**: Catty Agent（Netcatty 自动化） ｜ **环境**: Ubuntu 24.04 · GNOME on Wayland · Python 3.12
**关联**: 《RoboScope 部署日记（192.168.110.147）》（同一主机的 RoboScope/RF Dashboard 部署）

## 一、目标

为 192.168.110.147 部署 RIDE 图形 IDE（Robot Framework 编辑器），独立于 robot-test 与 RoboScope 的 venv。

## 二、安装结果

| 组件 | 版本 | 状态 |
| --- | --- | --- |
| wxPython | 4.2.2（gtk3 phoenix，wxWidgets 3.2.6） | ✅ wheel 安装（extras.wxpython.org ubuntu-24.04 源 / 本地 wheel 双通道） |
| robotframework-ride | 2.x | ✅ pip 安装，import 验证通过 |
| robotframework | 7.5 | ✅ pip 安装 |

* 独立虚拟环境: `~/ride-env`
* 验证命令: `~/ride-env/bin/python -c "import wx, ride; print('wx', wx.version()); import robot; print('robotframework', robot.version.VERSION)"`
* ⚠️ wxPython wheel 自带的原生依赖库（SDL2 等）位于 `~/ride-env/syslibs/usr/lib/x86_64-linux-gnu`，**必须**加入 `LD_LIBRARY_PATH`，否则 GUI 崩溃/无法启动

## 三、X11/Wayland 启动排障（关键经验）

* 桌面为 **GNOME on Wayland**（Xwayland 监听 :0），X 授权文件**不是**默认的 `~/.Xauthority`
* 实际授权文件: `/run/user/1000/.mutter-Xwaylandauth.4EAXX3`
  - ⚠️ 文件名是**点号连接**（`Xwaylandauth.<随机串>`），用短横线 glob（`.mutter-Xwaylandauth-*`）会匹配不到——这是本次排障走弯路的原因
* 症状: 直接 `DISPLAY=:0 ride` 报 `Authorization required, but no authorization protocol specified`
* 修复三件套:
  ```bash
  export DISPLAY=:0
  export XAUTHORITY=/run/user/1000/.mutter-Xwaylandauth.4EAXX3   # 桌面重新登录后随机串会变
  export LD_LIBRARY_PATH=~/ride-env/syslibs/usr/lib/x86_64-linux-gnu
  ```
* 日志中 `Gtk-CRITICAL ... gtk_box_gadget_distribute ... GtkScrollbar` 为 GTK3 无害警告，可忽略

## 四、启动脚本（已交付）

已创建 `~/ride.sh`（412 字节，chmod +x）：自动设置 DISPLAY、LD_LIBRARY_PATH，并 glob 最新 mutter 授权文件：

```bash
~/ride.sh                              # 前台启动
nohup ~/ride.sh > /tmp/ride.log 2>&1 & # 后台启动，日志 /tmp/ride.log
```

## 五、状态与遗留事项

* [x] 三组件安装并 import 验证通过
* [x] GUI 启动成功（PID 291779）；会话结束时进程已关闭，日常用 `~/ride.sh` 启动即可
* [ ] 桌面重新登录后若再报 Authorization required，重新 glob `/run/user/1000/.mutter-Xwaylandauth.*`（ride.sh 已自动处理）
* [ ] RIDE 与 `~/robot-test`（RF 7.5 + seleniumlibrary 6.9.0）用例目录打通尚未验证
* [ ] 可选：配置桌面快捷方式（.desktop 文件）实现图标启动