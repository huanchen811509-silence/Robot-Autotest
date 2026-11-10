# ADR-4X-15 · `workspace/` 生命周期纳入卸载、备份与忽略清单

- **状态**：已采纳
- **日期**：2026-11-09
- **决策人**：大师（书面批准 2026-11-09）
- **约束范围**：v4.1.0 起安装根目录、`uninstall.sh`、`backup.sh`、`.gitignore`、`update.sh`

## 背景

`FR-v4.1-04` ⑦ ⑧ 要求「卸载保留工作区、备份脚本包含 `workspace/`」。
工作区是**用户数据**（导出包、导入隔离区、任务产物），不是安装产物；
一旦随卸载删除，等同于删用户文件。另一面，它体积不可控（配额 512MB/项目），不能进 Git。

已知事实：`.gitignore:36` 已预留 `/workspace/`；`uninstall.sh` 的 `KEEP_DIRS` 已含 `workspace`；
`backup.sh` 已按 ADR 约定归档 `workspace`（v4.0.0 批次已落地，证据见 `docs/evidence/v4.0.0/EW40-05`）。

## 选项

| 选项 | 优点 | 代价 | 为何不选 / 为何选 |
|---|---|---|---|
| ① 工作区随安装目录一起删 | 卸载干净 | 删用户数据，不可接受 | 不选 |
| ② 工作区放系统临时目录 | 不占安装盘 | 重启/清理即丢，备份脚本覆盖不到 | 不选 |
| ③ 安装根 `workspace/` + 卸载保留 + 备份归档 + Git 忽略 | 路径确定、可备份、可解释 | 安装盘需要额外空间与配额管理 | **选** |

## 决定

- 唯一根：`${INSTALL_ROOT}/workspace/`；单项目目录名 = 项目 `code`（不含 `project_id`，便于跨实例交接）。
- `uninstall.sh`：`KEEP_DIRS` 必含 `workspace`；`--purge-data` 才删除，且删除前打印目录大小并要求交互确认。
- `backup.sh`：归档包含 `workspace/`；恢复指引与新版本升级脚本不覆盖 `workspace/` 内已有内容。
- `.gitignore`：保留 `/workspace/`；**任何**示例包一律放 `fixtures/workspace/`（体积小且有契约自检）。
- 配额与清理：默认 `app.workspace.max-project-bytes=512MB`、`max-files=20000`；
  超配额导出/导入直接拒绝并给出当前占用；清理只允许按项目显式删除，不做「按时间自动删」。

## 后果

- 正面：用户数据有确定位置、可备份、可跨实例搬运；卸载不会误删。
- 负面：安装盘容量需预留；需要运维在备份介质里为 `workspace/` 留空间。
- 触发复核的条件：工作区需要多机共享时，改为对象存储；届时 manifest 与签名契约保持不变。

## 关联

- WBS：`W41-02`、`W41-07`
- 相关文件：`scripts/ops/uninstall.sh`、`scripts/ops/backup.sh`、`.gitignore`、`docs/contracts/workspace-bundle.md`
- 上游依据：PRD `FR-v4.1-01` ⑦ ⑧，评估报告 §3「工作区落点」
