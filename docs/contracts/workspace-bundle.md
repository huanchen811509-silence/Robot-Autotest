# 工作区目录契约（workspace bundle contract）

**归属**：`FR-v4.1-01` / `FR-v4.1-02` ｜ **格式版本**：`1` ｜ **生效版本**：v4.1.0
**机检**：`python tools/check_workspace_bundle.py [--bundle <dir>]`（`--self-check` 为负向自证）

本文件是**唯一契约真源**：后端导出、导入预检、前端向导与门禁都以这里为准。
实现可以演进，但**目录名、顶层文件、`manifest.json` 字段、签名规范**任何一项变化都必须先改本文件与格式版本号。

## 1. 目录结构

工作区根：`${INSTALL_ROOT}/workspace/`（ADR-4X-15）。单项目一个目录，目录名 = 项目 `code`：

```
workspace/<project-code>/
├── project.json            # 项目元数据（格式版本、名称、描述、内核）
├── manifest.json           # 文件清单 + 服务端签名（ADR-4X-12）
├── members.json            # 成员引用（登录名 + 角色建议，不自动授权）
├── README.txt              # 人读说明：手工改动需显式导入才生效
├── cases/cases.json        # 用例台账（稳定键 case_code）
├── case-sets/case-sets.json# 用例集与条目（稳定键 case_code + seq_no）
├── assets/assets.json      # 设备 / 链路 / 项目资产（按名称+类型匹配）
├── assets/credentials.json # 凭据**引用**：名称 + 掩码指纹，绝无密文
├── environments/environments.json  # 环境画像
├── reports/reports.json    # 报告归档索引（不入执行表）
├── runs/runs.json          # 运行归档索引（不入执行表）
├── files/<robot_file.path> # 机器人源文件原样内容，路径即业务键
└── archive/                # 导入时可选的报告/运行归档副本（导出不产生）
```

规则：

- 7 个子目录（`cases`、`case-sets`、`assets`、`environments`、`reports`、`runs`、`files`）必须存在，空目录也保留。
- 顶层 4 个文件（`project.json`、`manifest.json`、`members.json`、`README.txt`）必须存在。
- `files/` 内路径等于 `robot_file.path`（相对项目根、统一 `/`）；单文件 ≤ 2MB（超限整包拒绝）。
  不设扩展名白名单（`.robot/.py` 之外的文本同样可交换）；恶意脚本扫描只针对 `.robot/.py`。
- **不允许**：绝对路径、盘符、`..` 段、空段、控制字符、大小写冲突路径；符号链接不跟随，
  导出按数据库内容重写文件，导入按包内文本落库。

## 2. `project.json`（schema v1）

```json
{
  "formatVersion": 1,
  "code": "DEMO",
  "name": "演示项目",
  "description": "可选",
  "testKernel": "ROBOT",
  "exportedAt": "2026-11-09T00:00:00Z",
  "generator": "robot-autotest/4.1.0",
  "granularity": "project",
  "rootPath": null
}
```

- `formatVersion` 必填且为整数；`code` 必须等于目录名（大小写不敏感比较）。
- `granularity` ∈ `project` | `dir` | `file`；`dir`/`file` 时 `rootPath` 为该粒度相对包根的路径。
- 缺字段或类型不符 → 预检失败，整包拒绝（不做字段猜测）。

## 3. `manifest.json` 与签名

```json
{
  "formatVersion": 1,
  "projectCode": "DEMO",
  "exportedAt": "2026-11-09T00:00:00Z",
  "generator": "robot-autotest/4.1.0",
  "granularity": "project",
  "rootPath": null,
  "files": [{"path": "project.json", "sha256": "<64 hex>", "bytes": 123}],
  "signature": {"algorithm": "HMAC-SHA256", "keyId": "server-local-v1", "value": "<64 hex>"}
}
```

- `files[]` 覆盖除 `manifest.json` 自身以外的**全部**文件（含 `README.txt` 与 `files/` 下每个文件）。
- 排序：`path` 字典序升序；`path` 相对包根、`/` 分隔。
- 校验：每个文件实际 sha256 与字节数必须与清单一致；清单与目录**双向**一致（多文件、少文件都判失败）。
- 签名对象：去掉 `signature` 后的清单**规范形 JSON**（键名字典序、无缩进、UTF-8）。
  算法 `HMAC-SHA256`，`value` 为 32 字节十六进制小写；比较使用常量时间。
- 密钥：`app.workspace.signing-key`（环境变量 `WORKSPACE_SIGNING_KEY`）优先；
  否则读取 `<workspace.root>/.signing-key`，不存在则生成 32 字节随机十六进制（0600）。
  `keyId` 固定 `server-local-v1`；密钥轮换需提升 keyId 并保留旧键校验能力，本期不做。
- 验签失败 → 整包拒绝，且不进入任何写入路径（隔离区也不写）。

## 4. 各域文件格式

### 4.1 `members.json`

```json
{"members": [{"login": "qa-user", "displayName": "QA", "role": "member"}]}
```

`login` 对应 `app_user.username`；`role` 仅作展示建议（`owner|member|viewer`）。
导入**不自动建用户、不自动授权**；缺失登录名 → skip 并报告（ADR-4X-13）。

### 4.2 `cases/cases.json`

```json
{"cases": [{"caseCode": "C-001", "title": "登录成功", "kind": "UI",
  "sourceType": "ledger", "priority": "P1", "domainTag": "smoke",
  "description": "可选",
  "steps": [{"stepNo": 1, "action": "Open Browser", "expectation": "打开登录页"}]}]}
```

`caseCode` 稳定；`kind` 对应 `test_case.case_kind`，`sourceType` 对应 `test_case.source_type`。
用例**没有**脚本外键列，脚本通过 `files/` 路径与用例文件自身约定关联，导入不重建该关系。
导入新建用例固定 `review_status=draft`、`source=import`、`safety_level=LX`（ADR-4X-13）。

### 4.3 `case-sets/case-sets.json`

```json
{"sets": [{"setCode": "SMOKE", "name": "冒烟集", "description": "",
  "items": [{"caseCode": "C-001", "seqNo": 1}]}]}
```

`setCode` 稳定且项目内唯一（对应 `test_set.set_code`）；导入建为 `kind=case_set`。

### 4.4 `assets/assets.json` / `assets/credentials.json`

```json
{"assets": [{"name": "设备A", "type": "DEVICE", "address": "10.0.0.1"}]}
```
```json
{"credentials": [{"name": "qa-admin", "credType": "SSH", "username": "qa",
  "mask": "***qa"}]}
```

凭据只允许：名称、认证类型、用户名、掩码（用户名末两位加 `***`）。
**禁止**出现 `password` / `passwd` / `secret` / `token` / `privateKey` 或任何可逆密文。

### 4.5 `environments/environments.json`

```json
{"environments": [{"name": "预发", "profileType": "SSH", "description": ""}]}
```

**不导出环境变量值**（`env_profile` 表不含该列，敏感值不入包）。

### 4.6 `reports/reports.json` / `runs/runs.json`

```json
{"reports": [{"reportNo": "RPT-1", "runId": "RUN-001", "title": "冒烟报告",
  "state": "SUCCEEDED", "createdAt": "2026-11-09T00:00:00Z"}]}
```
```json
{"runs": [{"runId": "RUN-001", "testSetCode": "SMOKE", "status": "SUCCEEDED",
  "startedAt": "2026-11-09T00:00:00Z"}]}
```

仅作归档索引；导入**不写 `run` / `test_report`**，仅在报告中列明 skip。

## 5. 引用映射（导入）

| 引用 | 稳定业务键 | 目标缺失 |
|---|---|---|
| 成员 | `login`（`app_user.username`） | skip + 报告；禁止自动 admin |
| 用例 | `caseCode` | skip + 报告 |
| 脚本文件 | `path`（`robot_file.path`） | 按策略写入（skip/overwrite/rename） |
| 用例集条目 | `caseCode` + `seqNo` | 该条 skip + 报告 |
| 设备/链路 | `name` + `type` | skip + 报告，不新建 |
| 凭据 | `name` + `mask` | 只写引用；不写密文 |

冷启动：目标实例无该项目 `code` → 默认拒绝并提示先建项目；显式「创建为草稿」时由导入者二次确认，
**不读 `members.json` 自动授权**。

## 6. 生命周期与只读语义

- 导出：读事务快照 + 项目级互斥（`workspace_job` 活动唯一键）；写临时目录后原子改名为正式目录。
- 导入：先预检（验签、哈希、路径、配额、冲突、脚本扫描）→ 用户确认 → 单事务落库。
- **手工改动目录不会自动生效**：目录是交换格式，不是运行时数据源；只能通过「导入」显式应用。
- 报告/运行只归档；重新执行必须回到平台页面，不从目录触发。

## 7. 配额与三级粒度

| 配额 | 默认值 | 超限行为 |
|---|---|---|
| 单项目文件数 | 20000 | 导出/导入拒绝，提示当前占用 |
| 单项目字节数 | 512MB | 同上 |
| 单文件字节数 | 2MB | 该文件拒绝，导入报告列明 |
| 单次操作时长 | 5 分钟 | 标记任务失败，可重试 |

粒度：`project`（整项目）；`dir`（`rootPath` 前缀，如 `files/`、`cases/`）；`file`（单文件）。
三种粒度共用同一 manifest/签名规则；`dir`/`file` 包同样带 `project.json`（`granularity`/`rootPath` 标明范围）。

## 8. 安全规则（硬性）

1. 路径统一走 `FileWorkspaceService.normalize` 同规则校验，拒绝绝对路径 / `..` / 盘符 / 控制字符。
2. 凭据密文永不落盘；掩码指纹为单向摘要。
3. 导入的 `.robot` / `.py` 逐文件扫描恶意模式（`os.system`、`subprocess`、`Runtime`/`ProcessBuilder`、
   `exec(`、`eval(`、`__import__`、`socket`、`ftplib`/`paramiko.Transport`、`shutil.rmtree`、
   Robot 的 `Evaluate` 关键字等）；
   命中 → 落隔离区 `workspace/.quarantine/<project>/<jobId>/`，默认不写入，需显式确认才导入并标记来源。
4. 签名 / 格式版本 / 路径安全任一失败 → 整包拒绝；冲突与缺引用只影响单条。
5. 审计：`workspace:export`、`workspace:import`、`workspace:import-quarantine` 三条动作必落 `audit_log`。

## 9. 版本策略

- `formatVersion` 只增不改语义；读端只接受 `1`。
- 更高格式版本 → 提示「由更新版本导出」并拒绝，不尝试降级解析。
- 迁移 `V11_28__v4_1_workspace_job.sql` 建任务表；已应用迁移不改字节。

## 10. 校验

```bash
# 仓库契约（ADR 已索引、迁移与 down 成对、TenantGuard 已登记、服务类存在）
python tools/check_workspace_bundle.py

# 具体包（含签名与逐文件哈希）
python tools/check_workspace_bundle.py --bundle workspace/DEMO
```
