# W4 / W56-41~42 主机资产派生与真机验证证据（v5.6.0）

采集日期：2026-11-14（GMT+8）
被测项目：95 `EHT-PYTEST-LIB`（kind=pytest-suite）
被测主机：gpu01 / 10.202.54.101（device 20）
被测用例：IM-MIX-011 `tests/nic/mixed/test_im_mix_011.py::test_im_mix_011_gds_platform`

## 一、结论

| 项 | 结论 | 证据 |
|---|---|---|
| 主机清单采集 | GPU 2 卡 / CPU 192 核 / DISK 含 rootfs，三项均 collected | `raw/collect-gpu01-assets.json` |
| inventory 派生 | `devices.gpus`、`devices.cpu`、`devices.storage` 由空对象变为真实条目 | `raw/inventory-before-devices.yaml`、`raw/inventory-after-devices.yaml` |
| 资源池与授权清单 | `resource_pool.{gpus,cpus,storages}` 与 `selected_targets` 同类同量，均带 `host/key` | `raw/run-env-after.yaml` |
| GPU 型号映射 | `model: pro-6000`、`vendor: nvidia` 由用例库规格表反查得到 | `raw/inventory-after-devices.yaml` |
| 真机单跑 | IM-MIX-011 PASS，run 结论 `reason=ok`，exit 0 | `raw/im-mix-011-junit-summary.json`、`raw/run_metadata.json` |
| 执行契约 | pytest `--help` 契约健康（cwd=用例库仓库根） | `raw/contract.json` |

真机 run id：`w56-42-real-005612`；产物目录 `/tmp/eht-real-runs/w56-42-real-005612`。

## 二、修复前的四处缺陷（都在「派生」这一层，逐层暴露）

1. **仓储取了摘要行**：`HostInventoryRepository.list()` 只查摘要列，`spec_json` 恒为 null，
   用它判断「有没有采到 GPU」永远得到「没有」。新增 `latest(projectId, deviceId)` 取完整记录。
2. **存储池写死空**：`storages` 列表已按盘填充，写池时却是 `pool.put("storages", List.of())`。
3. **授权清单只给 nic**：`selected_targets` 只写 `nic:`，框架先查池再查授权，
   于是 GPU 用例在 fixture 里被判「资源 gpu:…/gpu0 不在本次 selected_targets 范围」。
4. **GPU 缺 model/vendor**：采集只给型号全名，用例库按规格键取规格、按 vendor 选适配器。
   缺 model 报 `KeyError: 未登记 gpus 型号 None`，缺 vendor 报 `NotImplementedError: 厂商尚未适配`。

另修两处质量问题：`Map.copyOf` 打乱 GPU/盘序（同配置两次渲染哈希不同，证据无法比对）；
`loop*` 快照回环挂载混进存储池（实测一台机器 19 个只读几十兆的假盘）。

## 三、复现命令

```bash
# 采集（凭据必须挂在项目 95 下，平台级凭据按项目查不到）
curl -s -X POST http://127.0.0.1:8081/api/asset-host-inventory/collect \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"projectId":95,"host":"10.202.54.101","deviceId":20,"username":"inspur","credentialId":8,"confirm":true}'

# 真机单跑（mode 必须显式给 real：confirmReal 只是二次确认，不决定模式）
curl -s -X POST http://127.0.0.1:8081/api/projects/95/pytest/runs \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"runId":"w56-42-real-005612","mode":"real",
       "nodeIds":["tests/nic/mixed/test_im_mix_011.py::test_im_mix_011_gds_platform"],
       "hostKeys":["gpu01@10.202.54.101"],"confirmReal":true,"timeoutSeconds":900}'
```

## 四、遗留

- `vms` / `ovs` / `switches` 仍为空：资产台账没有这三类实体，派生不编造占位项。
- GPU 型号新增型号需在用例库 `config/specs/device_specs.yaml` 的 `gpus` 下补 `display_name` 与 `vendor`，
  平台侧无需改码；未登记时 inventory 的 `model`/`vendor` 留空并逐卡告警。
