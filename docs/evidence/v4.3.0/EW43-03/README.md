# EW43-03 · 迁移 `V11_29` 与端口网卡字段（W43-03）

**日期** 2026-11-10 ｜ **对应** `TC-v4.3-31`、`FR-v4.3-03`

## 1. 改动

| 位置 | 文件 | 改动 |
|---|---|---|
| 迁移 | `db/migration/V11_29__v4_3_device_port_nic.sql` | `device_port` 加 `nic_name VARCHAR(64) NULL`、`nic_index INT NULL` |
| 回滚 | `db/down/V11_29__v4_3_device_port_nic.sql` | `DROP COLUMN nic_index`、`DROP COLUMN nic_name` |
| 领域 | `backend/.../domain/asset/DevicePort.java` | 增 `nicName`、`nicIndex` 两字段（可空） |
| 仓储 | `backend/.../infrastructure/DeviceRepositoryJdbc.java` | 端口查询/写入补两列；新增 `portsOfVisibleDevices`、`listAll` |

## 2. 命令与读数

```bash
# WSL，读 ~/.config/robot-autotest/env 的库凭据
mysql -N -B -e "select version, description, success, installed_on from flyway_schema_history order by installed_rank desc limit 2"
mysql -N -B -e "show columns from device_port"
```

| 项 | 读数 |
|---|---|
| 迁移记录 | `11.29 / v4 3 device port nic / success=1 / 2026-11-10 13:55:56`（上一版 `11.28 / 2026-11-09 22:33:52`） |
| `device_port` 列数 | **16**，末两列为 `nic_name`、`nic_index` |
| 库内计数 | `device=5`、`device_port=0`、`link=2`、`project=12` |

## 3. 说明

- 两列均可空、无默认值，旧代码与旧调用不受影响（`TC-v4.3-32` 的兼容性依据）。
- **缺口**：`robot_it` 上「正向 → down → 再正向」三次演练未执行，`SchemaIT` 未单跑；`robot_dev` 无端口行（`device_port=0`），
  连接明细只能走空态。补齐方法见 `05-TEST-REPORT.md` §3。
