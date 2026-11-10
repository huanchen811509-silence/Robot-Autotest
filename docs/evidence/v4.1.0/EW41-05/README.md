# EW41-05 · 创建项目向导与 4 模板（FR-v4.1-04）

| 项 | 内容 |
|---|---|
| 工作包 | `W41-05` 创建项目向导 + 4 模板 |
| 日期 | 2026-11-09 |
| 环境 | 前端 `http://localhost:8001` · Chrome CDP `:9222` · 后端 `:8081` · 真库 `robot_dev` |
| 结论 | `W41_PROBE PASS`：向导 `10/10` 检查、viewer 视图 `3/3`、模板 schema 单测 `3/3` 全绿；真库读数与界面一致 |

---

## 1. 判据与真实读数

### 1.1 向导页（admin，`qa-e2e-admin`）

| 判据 | 真实读数 |
|---|---|
| 管理员五步 | `count=5`：`1模板 / 2基本信息 / 3运行设置 / 4成员 / 5确认`，进度 `第 1/5 步` |
| 四个模板 | `空白项目`（Robot Framework · 私有）、`接口回归`（Robot Framework · 项目内公开）、`Web UI`（Robot Framework · 项目内公开）、`pytest 服务`（pytest · 私有） |
| 未选模板拦截 | 点「下一步」→ `.wizard-message` = `请先选择一个模板` |
| 键盘可达 | 焦点落在第 3 张卡（`focusedIndex=2`），Space 选中 → `selectedIndex=2`、`pressed[2]=true`（原生 `<button type="button">`，`aria-pressed` 同步） |
| 单选 | 选后再选另一张 → `selectedCount=1`、`pressed=["false","true","false","false"]` |
| 代号必填 | 基本信息空提交 → `请填写项目代号` |
| 模板只做默认值 | 选「接口回归」后运行设置默认 `kernel=robot`、`visibility=project`（与 `config/project-templates/api-regression.json` 一致） |
| 回退保留输入 | 从运行设置返回基本信息 → `code` / `name` 仍在，`activeStep=2基本信息` |
| 成员添加 | 选中真实用户 id `18`（`HTTP E2E nobody`）→ `.wizard-members li` 1 条，展示 `HTTP E2E nobody / 测试工程师 / 移除` |
| 确认页 | 六行：项目代号 / 项目名称 / 说明 / 测试内核 / 可见性 / 成员（可选），进度 `第 5/5 步` |
| 创建成功 | 结果卡标题 `项目已创建`、`warnings=[]`、`projectId=85`（提交前最大项目 id 29） |

### 1.2 无成员管理能力视图（`qa-e2e-viewer`，`roles=[]`）

| 判据 | 真实读数 |
|---|---|
| 四步 | `count=4`：`1模板 / 2基本信息 / 3运行设置 / 4确认` |
| 无成员步骤 | `steps` 中不含 `4成员`，进度 `第 1/4 步` |
| 无成员表单 | `memberForm=false`，`roles=""`（无 `user:write` 能力，不摆越权入口） |

### 1.3 真库四处读数（`wizard-db-readings.log`）

| 读数 | 数值 | 说明 |
|---|---|---|
| `projects` 计数 | `11 → 12` | 仅最终「创建」提交新增 1 行，前四步无写库动作 |
| `project` 行 | `id=85 code=W41E2E-769631 kernel=robot visibility=project` | 与界面提交的代号 / 内核 / 可见性逐项一致 |
| `members=2` | 2 | 1 名创建人 + 1 名向导添加的工程师 |
| `member.role.engineer=1` | 1 | 成员角色落库为 `engineer`（向导默认） |
| `kernel.default=robot` | `robot` | 项目设置 `kernel.default` 与模板默认同源 |
| `audit.project:create=1` | 1 | 审计记为 `resource_type='project'`、`resource_id='85'`（该表 `project_id` 列为 NULL） |
| `test_sets=0` | 0 | 模板**不预置用例**（表名 `test_set`） |

### 1.4 模板 schema 单测（`templates-vitest.log`）

`vitest run src/config/projectTemplates.spec.ts` → `Test Files 1 passed`、`Tests 3 passed`（`validateTemplate` 正向 + 负向样本，含重复 key、非法内核、非法可见性）。

## 2. 实现口径

- 模板唯一真源 `config/project-templates/*.json`，`frontend/src/config/projectTemplates.ts` 只做加载与校验；`TEMPLATE_PROBLEMS` 非空即构建期判红。
- 向导步骤由 `capabilitiesForRole` 的 `user:write` 决定 5 / 4 步；模板卡为原生按钮，键盘 Space / Enter 均可选中。
- 唯一写库动作是确认页的 `POST /projects`；成员添加失败不回滚项目，逐条列入 `warnings`。
- 创建成功后 `auth.loadProjects()` + `auth.setProject(id)`，随后可跳工作区 / 工作台。

## 3. 判不了的部分（NOT-RUN）

| # | 项 | 原因 | 补齐 |
|---|---|---|---|
| 1 | 网络级「前四步 0 写请求」 | 探针以库计数差证明仅提交新增 1 行，未抓包逐请求断言 | 可在 CDP Network 录制 `POST/PUT` 计数 |
| 2 | axe-core 自动扫描 | 仓库 `package.json` / `node_modules` 均无 `axe-core`，未安装即不冒充通过 | 装 `axe-core` 后在向导页跑一次全规则扫描 |
| 3 | 成员添加失败降级路径 | 未构造「成员添加报错但项目成功」样本 | 需临时禁用某用户后再加为成员的负向样本 |
| 4 | 探针重复跑的项目样本 | 迭代期在 dev 库新增 `id=81/82/84`，最终有效样本为 `id=85`；均为真实 E2E 痕迹，非脚本伪造 | 保留并在测试库清理时一并归档 |
