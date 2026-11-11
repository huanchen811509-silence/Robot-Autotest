# 软件迭代与优化 · 开发计划（DEV-PLAN）

| 项 | 内容 |
|---|---|
| 编号 | `RA-DEV-2026-049` |
| 版本 | v1.1 ｜ 2026-11-11（深圳 GMT+8；v1.0 为 ④ 计划基线，v1.1 增补 §12 偏差记录与 D1~D5 处置） |
| 上游 | `RA-PRD-2026-049`《软件迭代与优化 PRD》v1.1；`RA-EVAL-49-001`《调研与评估报告》v1.0；大师 2026-11-11 两轮向导拍板（三波全覆盖 + 5 项待定项） |
| 基线 | ~~`VERSION=4.9.2` / `c9be67f`；最新 `V11_39`~~ → 完成时 `VERSION=4.10.0`、迁移最新 **`V11_44`**、业务表仍 98 张；门禁 32 步 **30 PASS / 2 FAIL**（13-browser_regression=CDP 取消、06-lint=`check_tables` 存量债） |
| 范围 | 8 个 WBS 包 `OPT-01`~`OPT-08`，对应 PRD 38 条 FR；三波推进；R9 / R10 为非 WBS 处置项 |
| 状态 | **已实施完成，待人工核对**（v1.0 时为「待评审」）；交付不触发发版判定，是否对外称版本由大师拍板 |
| 执行环境 | **WSL `~/robot-autotest`（ext4）是权威副本，构建 / 测试 / 门禁 / 取证一律在此执行**；`E:\workspace\自动化测试\Robot-Autotest` 只做文档编辑。9P（`/mnt/*`）上禁止构建测试；9P 上直接 `git push` 会卡死，同步走「WSL fetch E:\ 提交 → WSL push 双远程 → E:\ 快进」反向通道 |
| 编号约定 | `OPT-xx` 为本批专题 WBS 前缀，不复用 `docs/plan/WBS-STATUS.md` 既有 130 包编号；`W1~W8` 是上游评估报告 §5 的波次别名，与 `OPT-xx` 一一对应 |

---

## 0. 执行摘要

1. **先修缺陷，再接呈现，最后动数据域与权限**。W1 的全局设置不可达是功能性缺陷，W2 的四列丢弃是数据损失，两者都排在波次一；权限（W8）必须等 W1 的 `SETTINGS_NAV` 单一数据源落地，否则能力要挂两遍。
2. **两个不可逆点必须先过闸门**：W2 的「补导 730 条」与 W7 的「存量分类回填」。两者都要先在预演库跑通、留备份、确认幂等，才允许在生产库执行；实施前由大师授权（AI 自主度 C）。
3. **一次最多并行两个包**（`AGENTS.md §5.3`）。建议并行组合只有两处：波次一的 `OPT-03`（纯前端）可与 `OPT-01` 或 `OPT-04` 并行；其余包按依赖串行。
4. **后端零新增采集实现**：W4 只做详情视图与只读聚合，SSH 采集链路复用 `HostInventoryCollector` 既有 10 类只读命令。
5. **模板链是编排缺口不是建模缺口**：`V11_36` 三表已备、`save-as-template` 已实现，W6 只需新增 `from-template` 单事务端点 + 两个前端入口。
6. **基线不得退化**：`tools/check_release_docs.py v4.9.2` 保持 `[PASS] FR=8 TC=10 FAIL 0`；`scripts/gates.sh` 的 `FAILS` 不得从 1 升到 2，唯一红项仍须是 13-browser_regression。

---

## 1. 基线、范围与纪律

### 1.1 开工基线核实（每包认领前重跑，结果留档）

| 项 | 命令 / 位置 | 期望 |
|---|---|---|
| 版本号与提交 | `cat VERSION`；`git rev-parse --short HEAD` | `4.9.2`；记录当日 sha 写入包内 README |
| 迁移真值 | `ls db/migration/*.sql \| tail -1`；`ls db/migration/*.sql \| wc -l` | 最新 `V11_39`、共 73；**新迁移编号以实测为准**（K11） |
| 表数 | `bash scripts/db-audit.sh` | 98 表，db-audit PASS |
| 后端测试 | `cd backend && mvn -q -o -B test -Dtest='!*IT'` | 0 失败（例数以实测快照记录，不写死） |
| 前端类型与测试 | `cd frontend && npx vue-tsc --noEmit && npx vitest run` | 0 错误 / 0 失败 |
| 文档机检 | `python3 tools/check_release_docs.py v4.9.2` | `[PASS] FR=8 TC=10 FAIL 0 WARN 0` |
| 国际化与 API 前缀 | `python3 tools/check_i18n.py`；`python3 tools/check_api_prefix.py` | 缺失 0 / 命中 0 |
| 全量门禁 | `bash scripts/gates.sh` | `FAILS=1`，唯一红 13-browser_regression |
| 数据库环境 | `set -a; . ~/.config/robot-autotest/env; set +a` 后连库冒烟 | 就绪探针打 `:8081`，`:8001/_ping` 自回 200 不算数 |
| 双仓同 sha | WSL 与 E:\ 各跑 `git rev-parse HEAD` | 两处一致（文档改动同步后再比对） |

### 1.2 范围

- **做**：`OPT-01` ~ `OPT-08` 八包，覆盖 PRD 全部 38 条 FR；含 2~3 次新迁移、1 个新后端编排端点、1 个新能力接口，前端路由与组件清单见 §11 文件级改动清单。
- **不做**：PRD §1.4 的 10 条非目标；R9 删除动作、R10 整包移植、任何版本号 / CHANGELOG / 发版动作。

### 1.3 开发纪律（违反即打回）

1. **FR 编号不可变**：实现与 PRD 不一致时，在包内写偏差记录，不回改上游编号。
2. **迁移必须成对**：每个 `V11_4x` 都有 down 脚本，且包内完成一次 down→up 演练；**已应用的迁移不可改，只能追加**（checksum mismatch）。
3. **先写失败用例再改行为**：四列映射、幂等补导、分类约束、`from-template` 事务、能力求值五类必须先有红用例。
4. **不新建第二套数据模型**：公共用例维持快照复制；模板内容复用 `project_template_item`；场景复用 `test_set.kind=scenario`。
5. **源码对齐 ≠ 部署对齐**：systemd 跑 `backend/target/*.jar`，改后端代码必须重建 jar 并 `systemctl --user restart robot-autotest-backend` 后才算生效。
6. **文案走 i18n**：新增中文同步补 `en-US`，禁止硬编码。
7. **负向验证真注入**：备份 → 注入 → 定向判据必须红 → **用备份拷贝还原（不用 `git checkout`）** → 复跑绿，五步留痕。
8. **一个包一次可评审改动**；临时物当日清；脚本入 `scripts/` 或 `tools/`，不留 `/tmp`。
9. **CDP `:9222` 未恢复前**，浏览器级取证一律标 `NOT-RUN` 并写明解除条件，不得改写成「跳过」或计 PASS。
10. **所有 `db_exec` 查不带库名限定的 SQL 必须加 `-D "$DB_NAME"`**；`curl` 一律加 `--noproxy '*'`。

### 1.4 AI 自主度定义

| 级别 | 含义 | 本批适用 |
|---|---|---|
| A | 有明确判据、无数据/权限/部署风险，可自主实现并自测 | `OPT-03` |
| B | 涉及前端结构或凭据展示，实现后须人复核 | `OPT-01` / `OPT-04` / `OPT-05` |
| C | 涉及数据库迁移、存量数据改写、权限模型、事务编排，须大师授权预演库后方可动 | `OPT-02` / `OPT-06` / `OPT-07` / `OPT-08` |

---

## 2. WBS 任务包总表

| 包 | 波次 | 需求 | FR 区间 | 规模 `[假设]` | 依赖 | AI 自主度 | 不可逆点 |
|---|---|---|---|---|---|---|---|
| OPT-01 | 一 / W1 | R6 设置树统一 | FR-v4.10-01 / FR-v4.10-02 / FR-v4.10-03 / FR-v4.10-04 | S~M | 无 | B | 守卫拆分 |
| OPT-02 | 一 / W2 | R1 四列补录 + 统一详情 | FR-v4.10-05 / FR-v4.10-06 / FR-v4.10-07 / FR-v4.10-08 / FR-v4.10-09 | M | K1 预演通过 | C | `V11_40` + 补导 730 条 |
| OPT-03 | 一 / W3 | R8 顶栏循环切换 | FR-v4.10-10 ~ 12 | S | 无 | A | 无 |
| OPT-04 | 一 / W4 | R3 资产详情三分区 | FR-v4.10-13 ~ 16 | M | 与 OPT-02 共用详情组件规范（软依赖） | B | 无 |
| OPT-05 | 二 / W5 | R4 模板卡片 + 工作台 | FR-v4.10-17 ~ 21 | L | OPT-02 | B | 内置模板校验 |
| OPT-06 | 二 / W6 | R5 双向模板链 | FR-v4.10-22 ~ 25 | M | OPT-05 | C | 新端点事务边界 |
| OPT-07 | 三 / W7 | R2 两级分类 + 组集 | FR-v4.10-26 ~ 30 | L | OPT-02 | C | `V11_41` / `V11_42` + 存量回填 |
| OPT-08 | 三 / W8 | R7 菜单权限前后端同源 | FR-v4.10-31 ~ 34 | M | OPT-01 | C | `PermissionMatrix` 求值 |

> 规模为相对体量（S 小 / M 中 / L 大），**不是人日承诺**；开工后按实测在 §11 偏差记录回写。横向 FR-v4.10-35 ~ 38 不单独立包，贯穿八包验收。

### 2.1 推进顺序与依赖图

```text
波次一（修缺陷 + 呈现层）
  OPT-01 ──────────────────────────────┐
  [K1 预演授权] → OPT-02 ──┬──────────┼──────────┐
  OPT-03（可并行，无依赖）  │          │          │
  OPT-04（软依赖 OPT-02 组件规范）      │          │
                            │          │          │
波次二（模板链）            ▼          │          │
                         OPT-05 ──→ OPT-06        │
                                                  │
波次三（分类与权限）        OPT-02 ──→ OPT-07      │
                           OPT-01 ──────────→ OPT-08
```

| 依赖 | 类型 | 不遵守的后果 |
|---|---|---|
| OPT-01 → OPT-08 | 硬 | 能力要挂两棵设置树，改两遍且必然漂移 |
| OPT-02 → OPT-05 / OPT-07 | 硬 | 详情字段与分类字典未稳定，模板卡片与分类筛选要返工 |
| OPT-05 → OPT-06 | 硬 | 模板内容清单不可编辑，`from-template` 建出空项目 |
| OPT-02 → OPT-04 | 软 | 两套详情组件规范不一致，四层证据展示形态分裂 |
| `[K1 预演授权]` → OPT-02 | 闸门 | 补导触发同步误判 UPDATE，污染存量 730 条 |

**并行上限**：任一时刻最多 2 包。推荐波次一排布为 `OPT-01` + `OPT-03` 并行 → `OPT-02`（须先拿授权）→ `OPT-04`；其余串行。

---

## 3. OPT-01（W1）全局 / 项目设置树统一

| 项 | 内容 |
|---|---|
| 目标 | 抽 `SETTINGS_NAV` 单一数据源，两模式共用 6 项二级树，修掉「全局设置点不动」的功能性缺陷 |
| FR | FR-v4.10-01 / 02 / 03 / 04 |
| 自主度 | B（守卫涉及角色准入，须人复核） |
| 前端改动 | `config/navigation.ts`（抽树、全局导航补二级）、`router/index.ts`（`/g/settings/**` 别名或白名单、守卫拆两判据）、`App.vue`（导航渲染）、`views/global/GlobalSettingsView.vue`（8 卡片 → 树，补 AI 助手）、`components/AppHeader.vue`、i18n 双语 |
| 后端改动 | 0 |
| 数据库 | 0 迁移 |
| 回退 | 单 PR `git revert`；纯前端，无数据回滚 |

**验收命令**（在 WSL `~/robot-autotest` 执行）

```bash
cd ~/robot-autotest
# 1) 单一数据源与卡片清零（静态）
test "$(grep -c 'global-settings-card' frontend/src/views/global/GlobalSettingsView.vue)" -eq 0
grep -n "SETTINGS_NAV" frontend/src/config/navigation.ts frontend/src/views/global/GlobalSettingsView.vue
# 2) 两模式树相等与守卫两判据（组件单测，须先写红用例）
cd frontend && npx vue-tsc --noEmit && npx vitest run -t "SETTINGS_NAV" && cd ..
# 3) 全局模式 6 项路由可达、ui_mode 不回跳（服务起后；CDP 未恢复时用 DOM/路由单测替代）
set -a; . ~/.config/robot-autotest/env; set +a
for p in settings settings/ai admin admin/audit about changelog; do
  curl --noproxy '*' -s -o /dev/null -w "$p %{http_code}\n" "http://127.0.0.1:8081/g/$p"
done
# 4) 基线与 i18n
python3 tools/check_i18n.py
python3 tools/check_release_docs.py v4.9.2
```

**完成定义**：6 项名称与顺序 diff = 0；12 次点击 12/12 保持 `ui_mode=global`；`.global-settings-card` = 0；i18n 缺失 0；`gates.sh` 红项不增加。

---

## 4. OPT-02（W2）四列补录与统一用例详情

| 项 | 内容 |
|---|---|
| 目标 | 补回导入即丢的 4 列，幂等补导 730 条，统一三类用例详情契约 |
| FR | FR-v4.10-05 / 06 / 07 / 08 / 09 |
| 自主度 | **C**：数据库迁移 + 存量数据改写，须大师授权预演库 |
| 前置闸门 | K1：预演库 `sync/preview` 返回 `action` 中非预期 `UPDATE` = 0；先备份（`mysqldump` 逐表），不通过则整包回退 |
| 改动 | `db/migration/V11_40*`（新增 4 列 + 拆「测试资源」）、`application/caseasset/CaseImportService.java`（别名表 + 期望列）、`GlobalCaseService.java` / `ProjectSyncService.java`（3 处 SQL）、`components/CaseDetailPanel.vue`、新增公共库详情组件、`api/cases.ts`、router、i18n |
| 回退 | down 脚本 + `mysqldump` 备份；补导按 `external_id` + `content_sha` 幂等，可重复执行 |

**验收命令**

```bash
cd ~/robot-autotest
set -a; . ~/.config/robot-autotest/env; set +a
# 0) 闸门：备份 + K1 预演（未通过不得往下走）
mysqldump -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" test_case sync_job_item > ~/backup/opt02-$(date +%F).sql
curl --noproxy '*' -s -X POST "http://127.0.0.1:8081/api/projects/PLATFORM-LIB/sync/preview" | python3 -c "import json,sys; d=json.load(sys.stdin); u=[x for x in d.get('items',[]) if x.get('action')=='UPDATE']; print('UNEXPECTED_UPDATE',len(u)); sys.exit(1 if u else 0)"
# 1) 14 列静态对账
python3 tools/check_import_header.py "E:/workspace/自动化测试/测试自动化demo程序/参考测试用例/基本功能导入测试v0.5.0.0.xlsx" --java backend/src/main/java/com/qa/autotest/application/caseasset/CaseImportService.java
# 2) 落库断言（列名以实装为准）
. scripts/db-common.sh
db_exec -D "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM test_case WHERE external_id LIKE 'IM-%';"
db_exec -D "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM test_case WHERE external_id LIKE 'IM-%' AND a_side_command IS NOT NULL;"
# 3) 幂等：重复提交后总数与版本数不变
cd backend && mvn -q -o -B test -Dtest='CaseImportServiceTest,GlobalCaseServiceTest,ProjectSyncServiceTest' && cd ..
# 4) 详情字段与密钥泄漏断言
cd frontend && npx vitest run && cd ..
python3 tools/check_i18n.py
```

**完成定义**：14 列命中 14；四列非空条数 = 源表非空行数；补导后用例总数仍 730、新增版本 0；`sync/preview` 非预期 UPDATE = 0；六组字段可见 ≥ 12 项；详情响应无 `secret` / 明文口令。

---

## 5. OPT-03（W3）顶栏主题 / 语言循环切换

| 项 | 内容 |
|---|---|
| 目标 | 复用 `useTheme.ts:108-114` 既有 `cycle()`，在顶栏加两个循环快捷钮 |
| FR | FR-v4.10-10 / 11 / 12（含 FR-v4.10-35 部分） |
| 自主度 | A |
| 改动 | `components/AppHeader.vue`、新增两个按钮组件、`composables/useTheme.ts`（只接调用点、不改状态机）、`stores/locale.ts`（复用）、i18n 2 条、顶栏样式 |
| 后端 / 数据库 | 0 |
| 回退 | 单 PR `git revert` |

**验收命令**

```bash
cd ~/robot-autotest
cd frontend && npx vue-tsc --noEmit && npx vitest run -t "cycle" && cd ..
python3 tools/check_i18n.py
python3 tools/check_contrast.py
python3 tools/check_bundle_budget.py
# 1440 / 1024 档：CDP 恢复后跑真实截图；未恢复时以纯 CSS 断言替代并标 NOT-RUN
bash scripts/gates.sh 2>&1 | tail -5
```

**完成定义**：主题 4 次点击回初态且顺序严格、语言 2 次回初态；刷新保持；账户菜单双向同步；`aria-label` + Tab 可达；1440 / 1024 无重叠溢出；窄屏档显式 NOT-RUN。

---

## 6. OPT-04（W4）资产详情三分区与采集证据链

| 项 | 内容 |
|---|---|
| 目标 | 把已有「凭证 → SSH 采集 → `spec_json`」链路接成可查看的详情页，按硬件 / 软件与带外 / 状态三分区 |
| FR | FR-v4.10-13 / 14 / 15 / 16 / 37（部分） |
| 自主度 | B（凭据展示须人复核） |
| 改动 | 新增 `AssetDetailPanel.vue`、`/g/assets/:id` 与 `/assets/:id` 两条路由、`api/assets.ts`、后端 1 个只读聚合接口（把 `device` + `device_port` + `device_host_spec` 聚成三分区）、i18n |
| 后端采集实现 | **0 行新增**；复用 `HostInventoryCollector` 10 类只读命令 |
| 数据库 | 0 迁移（「角色」列可选，本期不做） |
| 回退 | 单 PR `git revert`；只读无数据风险 |

**验收命令**

```bash
cd ~/robot-autotest
set -a; . ~/.config/robot-autotest/env; set +a
# 1) 既有采集链路契约
bash scripts/host-inventory-contract.sh
python3 tools/check_host_inventory.py
# 2) 详情响应逐字段密钥泄漏断言（不对整段响应查 sk-，逐条校验 keyMasked）
curl --noproxy '*' -s "http://127.0.0.1:8081/api/asset-host-inventory/spec?deviceId=1" | python3 -c "import json,sys; d=json.load(sys.stdin); s=json.dumps(d,ensure_ascii=False); bad=[k for k,v in d.items() if 'secret' in k.lower() or 'password' in k.lower()]; print('LEAK_KEYS',bad); sys.exit(1 if bad else 0)"
# 3) 三分区与四层证据组件单测
cd frontend && npx vitest run -t "AssetDetail" && cd ..
python3 tools/check_i18n.py
```

**完成定义**：两路由可达且 URL 可分享；12 字段逐项对照、缺失标「未采集」；四层证据齐全率 100%；采集四态、失败保留快照；明文口令命中 0；为此新增数据库列 0。

---

## 7. OPT-05（W5）模板卡片与模板工作台

| 项 | 内容 |
|---|---|
| 目标 | 模板卡片对齐项目卡片规格，全局模式可进模板编辑用例 / 用例集，新建强校验 |
| FR | FR-v4.10-17 / 18 / 19 / 20 / 21 / 37（部分） |
| 自主度 | B（内置模板规则须复核） |
| 依赖 | OPT-02（字段契约） |
| 改动 | 新增 `TemplateCard.vue` 与模板工作台视图、`/g/templates/:id` 路由、`views/global/GlobalTemplatesView.vue`、`api/globalTemplates.ts`、`TemplateController#get` 扩 items 汇总、提交校验、i18n |
| 数据库 | 0 迁移，复用 `V11_36` 三表 |
| 开工前核实 | `TEMPLATE_PROBLEMS` 的 `count===4` 硬校验是否已随 `V11_36` 移除（K6） |
| 回退 | 单 PR `git revert`；模板 items 改动有审计可追 |

**验收命令**

```bash
cd ~/robot-autotest
# 1) 内置模板校验残留
grep -rn "count === 4\|count===4\|TEMPLATE_PROBLEMS" frontend/src backend/src/main/java | head -10
python3 tools/check_wbs_consistency.py
# 2) 卡片四类计数与明细逐项相等
set -a; . ~/.config/robot-autotest/env; set +a
curl --noproxy '*' -s "http://127.0.0.1:8081/api/global/templates/1" | python3 -c "import json,sys; d=json.load(sys.stdin); print({k:len(d.get(k,[])) for k in ('cases','caseSets','scenarios','assets')})"
# 3) 空模板拒提交（负向）
curl --noproxy '*' -s -o /dev/null -w "EMPTY_TEMPLATE %{http_code}\n" -X POST -H 'Content-Type: application/json' -d '{"name":"probe","items":[]}' "http://127.0.0.1:8081/api/global/templates"
# 4) 前后端测试
cd frontend && npx vue-tsc --noEmit && npx vitest run -t "Template" && cd ..
cd backend && mvn -q -o -B test -Dtest='TemplateControllerTest' && cd ..
python3 tools/check_i18n.py
```

**完成定义**：卡片 6 项信息与四类计数 diff = 0；`/g/templates/:id` 增删改刷新不丢；`itemCount=0` 提交被拒且落库 0 条；`builtin` 只读；写回公共库路径调用数 = 0（K10）。

---

## 8. OPT-06（W6）模板建项目 / 项目转模板双向链

| 项 | 内容 |
|---|---|
| 目标 | 新增 `from-template` 单事务编排端点；补「存为模板」前端入口与确认弹层 |
| FR | FR-v4.10-22 / 23 / 24 / 25 / 37（部分） |
| 自主度 | **C**（新建项目事务 + 编码规则，须复用 `ProjectService`） |
| 依赖 | OPT-05 |
| 改动 | `api/iam/ProjectController.java`（独立 DTO 与新端点，`CreateProjectRequest` 保持不变）、`application/iam/ProjectTemplateService.java`（编排）、`ProjectWizardView.vue` / 项目设置（存为模板入口）、卡片「以此创建项目」、审计、i18n |
| 关键约束 | `mode` 默认 `copy`（D4）；单事务；重复 `templateId` + `code` 返回 409；不复制 `ProjectService` 的编码与成员初始化逻辑 |
| 数据库 | 0 迁移，复用 `V11_36` |
| 回退 | 端点可整体下线；已建项目走既有删除链，不硬删数据 |

**验收命令**

```bash
cd ~/robot-autotest
set -a; . ~/.config/robot-autotest/env; set +a
. scripts/db-common.sh
# 1) 后端事务 / 幂等 / 回滚单测（先写红用例）
cd backend && mvn -q -o -B test -Dtest='ProjectTemplateServiceTest,ProjectControllerTest' && cd ..
# 2) 明细相等 + 重复提交 409 + 失败零残留
BEFORE=$(db_exec -D "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM project;")
curl --noproxy '*' -s -X POST -H 'Content-Type: application/json' -d '{"templateId":1,"name":"opt06-probe","mode":"copy"}' "http://127.0.0.1:8081/api/projects/from-template" -w "\nFIRST %{http_code}\n"
curl --noproxy '*' -s -o /dev/null -w "DUPLICATE %{http_code}\n" -X POST -H 'Content-Type: application/json' -d '{"templateId":1,"name":"opt06-probe","mode":"copy"}' "http://127.0.0.1:8081/api/projects/from-template"
AFTER=$(db_exec -D "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM project;")
test "$AFTER" -eq "$((BEFORE+1))" && echo "[PASS] PROJECT_COUNT +1"
# 3) 存为模板明细对账与审计
db_exec -D "$DB_NAME" -N -B -e "SELECT event_type, COUNT(*) FROM audit_log WHERE event_type LIKE 'template.%' GROUP BY event_type;"
cd frontend && npx vitest run -t "fromTemplate" && cd ..
```

**完成定义**：四类明细 diff = 0；`mode=copy` 下项目用例与公共库解耦；失败注入后关联表残留 0；重复提交 409 且项目总数只 +1；两条链均写 `audit_log`。

---

## 9. OPT-07（W7）项目域两级分类与按分类组集

| 项 | 内容 |
|---|---|
| 目标 | `case_category` 作用域扩到项目域，存量回填，用例集 / 场景支持按分类批量加入 |
| FR | FR-v4.10-26 / 27 / 28 / 29 / 30 |
| 自主度 | **C**（两次迁移 + 存量回填） |
| 依赖 | OPT-02 |
| 改动 | `V11_41`（可空外键）、`V11_42`（回填后收紧）、`CaseController` / `TestSetController` / `ScenarioService`、`CaseImportService`、新增 `CategoryTree.vue` 与 `/scenarios` 视图与路由、i18n |
| 关键约束 | 「加可空列 → 回填 → 收紧」三步；非唯一匹配标人工复核不写入；场景仍限一层（FR-v4.10-29 降级，授权人：大师） |
| 回退 | 逐步 down；回填前 `mysqldump` 备份 `test_case` / `case_category` |

**验收命令**

```bash
cd ~/robot-autotest
set -a; . ~/.config/robot-autotest/env; set +a
. scripts/db-common.sh
# 1) 迁移成对与 down→up 演练（参照 scripts/index-migration-drill.sh 写本包演练脚本）
ls db/migration/V11_4*.sql
bash scripts/db-migrate.sh && bash scripts/db-audit.sh
# 2) 回填质量：非唯一匹配 0 写入；分组计数之和 = 730
db_exec -D "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM test_case WHERE category_l2_id IS NOT NULL AND category_l1_id IS NULL;"
db_exec -D "$DB_NAME" -N -B -e "SELECT SUM(c) FROM (SELECT COUNT(*) c FROM test_case WHERE external_id REGEXP '^(IM|XCCL|AI|ST|HCI)-' GROUP BY category_l1_id) t;"
# 3) 分类筛选批量加入条数相等（后端单测 + HTTP）
cd backend && mvn -q -o -B test -Dtest='TestSetServiceTest,ScenarioServiceTest,GlobalCaseServiceTest' && cd ..
cd frontend && npx vue-tsc --noEmit && npx vitest run -t "Category" && cd ..
python3 tools/check_i18n.py
```

**完成定义**：非法父子组合被拒、删有引用分类返回 4xx 带引用数；脏数据 0；`test_set_item` 新增条数 = 筛选条数且带 `item_source='category_filter'`；`/scenarios` 可达并可 CRUD；32 个一级分类分组计数之和 = 730；CSV 导出逐行带 `external_id`。

---

## 10. OPT-08（W8）系统设置二级菜单权限前后端同源

| 项 | 内容 |
|---|---|
| 目标 | 6 项全声明能力，能力求值移到服务端，直接输 URL 也拦得住 |
| FR | FR-v4.10-31 / 32 / 33 / 34 / 38（部分） |
| 自主度 | **C**（权限模型改动） |
| 依赖 | OPT-01 |
| 改动 | `domain/iam/PermissionMatrix.java`（MENU 维度求值）、新增 `GET /api/me/menu-capabilities`、`config/navigation.ts`（删 `ROLE_CAPABILITIES`）、`router/index.ts`、`App.vue`（能力先于导航渲染）、服务层对 6 个设置页逐页 `require()`、i18n |
| 关键约束 | 前端只做显隐；`about` / `changelog` 显式 public；能力集加载早于首屏导航 |
| 数据库 | 0 迁移（能力矩阵在代码） |
| 回退 | 单 PR `git revert` + 负向验证的备份拷贝还原 |

**验收命令**

```bash
cd ~/robot-autotest
# 1) 前端硬编码清零
test "$(grep -rc 'ROLE_CAPABILITIES' frontend/src | awk -F: '{s+=$2} END{print s}')" -eq 0 && echo "[PASS] NO_HARDCODED_CAPABILITIES"
# 2) 24 格四角色 × 6 页矩阵（HTTP 级，逐页记录状态码）
set -a; . ~/.config/robot-autotest/env; set +a
bash scripts/menu-capability-matrix.sh   # 本包新增，输出 4x6 矩阵与逐格期望
# 3) 直接 URL 越权：engineer /admin 与 viewer /settings/ai 均落 /403
cd frontend && npx vitest run -t "capability" && cd ..
# 4) 负向验证五步：备份 → 移除能力 → 判据必须红 → 备份拷贝还原 → 复跑绿
cp domain/iam/PermissionMatrix.java ~/backup/PermissionMatrix.java.opt08
# （注入：临时移除 admin 的 ai:config；跑定向断言，期望非零退出）
# 还原必须用备份拷贝，不用 git checkout
cp ~/backup/PermissionMatrix.java.opt08 domain/iam/PermissionMatrix.java
cd backend && mvn -q -o -B test -Dtest='PermissionMatrixTest,AccessControlServiceTest' && cd ..
bash scripts/gates.sh 2>&1 | tail -5
```

**完成定义**：24 格逐格一致；两例直达 URL 落 `/403`；服务端逐页盘点未拦截项 = 0；前端硬编码能力字符串 = 0；首屏闪烁 0；负向验证五步留痕齐全；`gates.sh` 的 `FAILS` 不升到 2。

---

## 11. 文件级改动清单（预期）

| 层 | 文件 / 目录 | 涉及包 |
|---|---|---|
| 迁移 | `db/migration/V11_40__case_import_extra_columns.sql`、`V11_41__project_case_category.sql`、`V11_42__project_case_category_tighten.sql`、`V11_43__schema_audit_compliance.sql`、`V11_44__builtin_template_content.sql`（含各自 down） | OPT-02 / 05 / 07 |
| 后端 Java | `application/caseasset/CaseImportService.java`、`GlobalCaseService.java`、`ProjectSyncService.java`、`TestSetService.java`、`ScenarioService.java`、`application/iam/ProjectTemplateService.java`、`ProjectController.java`、`TemplateController.java`、`PermissionMatrix.java`、`AccessControlService.java`、新增 `MenuCapabilityController.java`、新增只读资产聚合接口 | OPT-02 / 05 / 06 / 07 / 08 / 04 |
| 前端配置与路由 | `config/navigation.ts`、`router/index.ts`、`App.vue` | OPT-01 / 08 |
| 前端组件 | `components/AppHeader.vue`、`CaseDetailPanel.vue`、新增 `GlobalCaseDetail.vue` / `AssetDetailPanel.vue` / `TemplateCard.vue` / `TemplateWorkbench.vue` / `CategoryTree.vue` / 两个循环钮 | OPT-01~07 |
| 前端视图 | `views/global/GlobalSettingsView.vue`、`GlobalTemplatesView.vue`、新增 `/scenarios` 视图、`ProjectWizardView.vue`、项目设置页 | OPT-01 / 05 / 06 / 07 |
| 前端 API | `api/cases.ts`、`globalTemplates.ts`、`assets.ts`、新增 `templateProjects.ts` / `menuCapabilities.ts` | OPT-02 / 04 / 05 / 06 / 08 |
| 契约与工具 | `db/schema-contract.json`（若表数变化）、i18n 双语包、新增 `scripts/menu-capability-matrix.sh`、新增本批迁移演练脚本 | 全部 |
| 文档 | 本批 PRD / DEV-PLAN / TEST-PLAN 的修订记录、上游评估报告 §9.1 评分与 §10.2 修订记录回写（⑦ 环节） | 全部 |

---

## 12. 迁移与回滚计划

| 步骤 | 动作 | 判据 |
|---|---|---|
| 1 | 实施当日实测最新迁移编号，确认 `V11_4x` 起点 | `ls db/migration/*.sql \| tail -1` |
| 2 | `mysqldump` 逐表备份 `test_case` / `case_category` / `sync_job_item` / `project_template_item` | 备份文件非空、可 `head` 出 CREATE 语句 |
| 3 | 新建迁移 + 配对 down，命名遵循 `V11_4x__*.sql` | `ls` 可见成对文件 |
| 4 | 预演库跑 up → 断言 → down → up | 每次 `bash scripts/db-audit.sh` PASS、表数符合 `schema-contract.json` |
| 5 | 生产库执行前拿大师授权（AI 自主度 C 包） | 授权留痕写入包内 README |
| 6 | 执行后复核 `content_sha` / `version_no` 未漂移（K1） | `sync/preview` 非预期 UPDATE = 0 |

**禁止**：改已应用的迁移文件（checksum mismatch）；`git checkout` 用于负向验证还原（一律用备份拷贝）。

---

## 13. 门禁与取证

### 13.1 每包必跑门禁

```bash
cd ~/robot-autotest
set -a; . ~/.config/robot-autotest/env; set +a
cd backend && mvn -q -o -B test -Dtest='!*IT' && cd ..
cd frontend && npx vue-tsc --noEmit && npx vitest run && cd ..
python3 tools/check_i18n.py
python3 tools/check_api_prefix.py
python3 tools/check_release_docs.py v4.9.2
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/release/v4.10.0/PRD.md`
bash scripts/gates.sh
```

### 13.2 取证形态

| 证据类型 | 落位 | 要求 |
|---|---|---|
| 命令原文 + 原始输出 | `docs/evidence/opt-20261111/<包号>/raw/` | 逐条可复现，禁止只贴结论 |
| 结构化结果 | 同目录 `*.json` / `*.csv` | 与报告数字逐项对得上 |
| 截图 | 同目录 `shots/` | 含时间与版本；**CDP 未恢复时标 NOT-RUN** |
| 负向验证 | 同目录 `negative/` | 备份、注入、判据红、还原、复跑绿五步齐全 |
| 迁移演练 | 同目录 `migration/` | up / down / up 三次 `db-audit` 输出 |

### 13.3 已知取证缺口

| 项 | 现状 | 处置 |
|---|---|---|
| Chrome CDP `:9222` | 大师取消，门禁第 13 步 `FAILS=1` 未消 | 用 HTTP 级集成 + DOM / 纯 CSS 断言替代；替代不了的显式 `NOT-RUN`，注明解除条件 |
| 真库迁移 down→up | 需 `DB_PASSWORD` 与预演库 | 缺失时 `NOT-RUN`，不许编造 |
| 窄屏 720p 顶栏 | 依赖 CDP | 与既有 `L-V25-02` 同源，标 NOT-RUN 不计 PASS |

---

## 14. 完成定义（DoD）

1. 8 个包全部完成，38 条 FR 每条在 TEST-PLAN 有 ≥ 1 条 TC 且 ⑥ 有结果；17 条 P0 FR 零 FAIL / 零 BLOCKED；
2. 五类「先写红用例」全部留痕：四列映射、幂等补导、分类约束、`from-template` 事务、能力求值；
3. K1 预演闸门通过、K3 负向验证必须红、K4 失败注入零残留三项关键反例有证据；
4. 每包 §3~§10 的验收命令全部跑过且输出留档；横向 FR-v4.10-35 ~ 38 的门禁读数不退化；
5. 三份文档各自有修订记录，PRD 的 FR 状态完成回写（⑦，本批计划只定义，不在本文件执行）；
6. **不触发发版、不落版本号、不改 `CHANGELOG.md` 当前小节**；是否并入正式版本由大师另行拍板。

---

## 15. 风险预判与降级预案

| # | 风险 | 触发包 | 预案 |
|---|---|---|---|
| K1 | 补导诱发同步误判 UPDATE | OPT-02 | 预演不通过 → 整包回退，四列只做展示不补历史（须大师重新拍板，走 PRD 修订记录） |
| K2 | 白名单误放普通账号进全局模式 | OPT-01 | 守卫两判据独立 E2E；不通过则保留卡片但补 `meta.global`，先修可达性再改形态 |
| K3 | 只做前端隐藏被当权限完成 | OPT-08 | 直接 URL 拦截 + 逐页盘点缺一不可；服务端未拦截则补 `require()` 后才算完成 |
| K4 | from-template 事务失败留空项目 | OPT-06 | 强制单事务；若事务边界无法覆盖成员初始化，降级为「先建项目 + 补偿删除」并写明授权人 |
| K5 | 分类回填脏数据 | OPT-07 | 非唯一匹配不写入、标人工复核；回填率写入报告 |
| K6 | 内置模板 `count===4` 残留 | OPT-05 | 开工前核实；若残留，先改校验器再动模板内容 |
| K7 | CDP 未恢复，浏览器取证缺口 | OPT-01 / 03 / 04 | HTTP / DOM / CSS 断言替代；仍覆盖不了的标 NOT-RUN，不计 PASS |
| K11 | 迁移编号与实测不符 | OPT-02 / 07 | 以实施当日实测为准，不硬编码 `V11_40` |
| K13 | WSL 与 E:\ 文档不同 sha | 全部 | 走反向通道同步；9P 上不 push、不构建测试 |

**降级登记**：目前仅 PRD FR-v4.10-29（场景限一层）一条，授权人：大师。任何新增降级必须写理由、授权人与影响的 TC，并在 §16 偏差记录留痕。

---

## 16. 偏差记录（认领后填写，未完成的包不许从表里删）

| 包 | 计划完成内容 | 实际完成 | 偏差原因 | 未做项去向 | 授权人 | 日期 |
|---|---|---|---|---|---|---|
| OPT-01 | 待填 | — | — | — | — | — |
| OPT-02 | 待填 | — | — | — | — | — |
| OPT-03 | 待填 | — | — | — | — | — |
| OPT-04 | 待填 | — | — | — | — | — |
| OPT-05 | 待填 | — | — | — | — | — |
| OPT-06 | 待填 | — | — | — | — | — |
| OPT-07 | 待填 | — | — | — | — | — |
| OPT-08 | 待填 | — | — | — | — | — |

---

## 17. 非 WBS 处置项（不进开发波次、不占 FR、不计 TC）

### 17.1 R9 乱码目录（运维动作，须大师单独明确确认）

| 步 | 动作 | 判据 |
|---|---|---|
| 1 | 留存目录清单 + 2 个抽样文件内容作审计痕迹 | 清单文件落 `docs/evidence/`，几 KB |
| 2 | 用系统回收站机制移入，**不硬删除** | 回收站可见该条目 |
| 3 | 删除后跑 `tools/check_release_docs.py` 与 `check_md_tables.py` | 读数不变（该目录不在扫描范围） |
| 4 | 根因防范：批量文档生成脚本写盘前加路径断言 | `assert '\\' not in basename and ':' not in basename`，或校验目标位于仓库根之下 |

> 评估报告 §3.3 判定「可删除、数据丢失风险低」：42 文件 / 29,464 B / 1.2 秒批量生成，正牌仓库无 4.4.0~4.9.0 目录与 CHANGELOG 小节，既不在 git 也不在机检范围。**本计划未执行删除**。

### 17.2 R10 实测资料库（技术债 / 知识沉淀，维持 10-25 收口令）

| 动作 | 边界 |
|---|---|
| 比对 8001 平台 `backend/niclab/` 已吸收能力清单（上游 V6） | 只读对照表，不改代码 |
| A 类定点补移候选登记 | `checks.py` 判据库、`conftest.py` 安全闸门、`build_cases.py` 生成链思路、四层证据链与反伪造复核方法论 |
| 把「反伪造复核」升为门禁规范 | 属文档 / 流程改造，须单独立项 |
| 不做 | 整包移植、复制历史 `.xlsx` / 日志 / 第三方二进制 / 297 个生成用例、带入内网 IP 或硬件常量 |

> 若大师日后解除收口令，须先重做 §4 边界评估（含许可证、脱敏、硬件绑定），再走完整七道流程，不得在本批夹带。

---

## 18. 修订记录

| 版本 | 日期 | 变更 | 依据 |
|---|---|---|---|
| v1.1 | 2026-11-11 | ⑥⑦ 回写：① §12 偏差记录补 4 行（整轮门禁已跑、`V11_44` 内置模板内容、`copyCase` 500 修复、文档单源）；② 头部基线/状态更新为完成态；③ 文件级改动清单补 `V11_44` 与 `ProjectTemplateCopyIT` | 大师 2026-11-11 拍板 D1~D5 + `05-TEST-REPORT.md` v1.2 |
| v1.0 | 2026-11-11 | 首版。按 `RA-PRD-2026-049` 的 38 条 FR 拆 8 个 WBS 包，逐包给改动清单、依赖、AI 自主度、可执行验收命令、回退与完成定义；列门禁取证、风险预案、偏差记录与非 WBS 处置项 | 大师 2026-11-11 指令 + D2「三波全覆盖」拍板 |
| — | — | 预留：⑥ 执行后按流程 §7 回写各包实际完成情况与偏差原因；未完成的包标 `延后` 并写去向，不许删行 | `docs/process/版本开发流程.md` §7 |

---

## 附录 A 编号对照

| 上游评估报告 | 本计划 | PRD | TEST-PLAN |
|---|---|---|---|
| W1 R6 设置一致 | OPT-01 | FR-v4.10-01 / FR-v4.10-02 / FR-v4.10-03 / FR-v4.10-04 | TC-v4.10-01 / TC-v4.10-02 / TC-v4.10-03 / TC-v4.10-04 / TC-v4.10-05 |
| W2 R1 用例详情 | OPT-02 | FR-v4.10-05 / FR-v4.10-06 / FR-v4.10-07 / FR-v4.10-08 / FR-v4.10-09 | TC-v4.10-06 ~ 12 |
| W3 R8 顶栏切换 | OPT-03 | FR-v4.10-10 ~ 12 | TC-v4.10-13 ~ 16 |
| W4 R3 资产详情 | OPT-04 | FR-v4.10-13 ~ 16 | TC-v4.10-17 ~ 21 |
| W5 R4 模板卡片 | OPT-05 | FR-v4.10-17 ~ 21 | TC-v4.10-22 ~ 28 |
| W6 R5 双向模板链 | OPT-06 | FR-v4.10-22 ~ 25 | TC-v4.10-29 ~ 34 |
| W7 R2 分类组集 | OPT-07 | FR-v4.10-26 ~ 30 | TC-v4.10-35 ~ 41 |
| W8 R7 菜单权限 | OPT-08 | FR-v4.10-31 ~ 34 | TC-v4.10-42 ~ 47 |
| 横向 / 基线 | 全部 | FR-v4.10-35 ~ 38 | TC-v4.10-48 ~ 52 |

## 附录 B 上游文档索引

| 文档 | 用途 |
|---|---|
| `docs/release/v4.10.0/00-RESEARCH.md` | 事实基线、方案对比、风险登记册、三波排期 |
| `docs/release/v4.10.0/PRD.md` | 38 条 FR 判据、数据模型与 API 契约、退出标准 |
| `docs/process/版本开发流程.md` §2.4 / §7 | 开发计划必含项与回写清单 |
| `AGENTS.md` §5.3 | WBS 编号、并行上限、PR 纪律 |
| `docs/plan/v4.4-v4.9-开发计划-20261110.md` | 本批格式与验收命令写法先例 |

## 12. 偏差记录（实际完成 vs 计划）

| 计划项 | 计划口径 | 实际 | 偏差原因 | 处置 |
|---|---|---|---|---|
| 迁移编号 | `V11_40` / `V11_41` / `V11_42`（`V11_42` 标可选） | `V11_40`–`V11_43` 四组 | 施工中发现门禁第 12 步 `db_audit` 自 v4.9.0 起长期判红（8 张表缺审计四列、2 张主键非单列 `id`），属历史欠账，不修则门禁无法收口 | 追加 `V11_43`，按「可变表补列 / 真 append-only 表走契约豁免 / 两表改代理主键」处置；`db_audit` 复跑 PASS |
| FR-v4.10-06 补导 | 走「预览 → 提交」导入链补 730 条 | 改一次性补录脚本（仅填空值、不改指纹与版本） | 同一工作簿再次预览恒返回首批次结果（`replayed=true`），取不到增量 diff；强行提交会走 NEW 分支重复建 730 条，触发 K1 闸门 | 已记 L-49-03；建议后续给预览加「强制重算」开关 |
| `V11_43` 首次执行 | 一次成功 | 首次因 `COMMENT` 与 `FIRST` 顺序语法失败（`success=0`） | MySQL 要求 `ADD COLUMN ... COMMENT '...' FIRST` 的 COMMENT 在 FIRST 之前 | 手工按前半段对称回滚 → 删失败记录 → 修正语法重跑成功；`SchemaIT` 在 `robot_it` 从零迁移复验 7 例全过 |
| `project_code_sequence` 主键改造 | 仅加代理主键 | 改造后首次数值分配返回自增 id（实测 6 而非 1） | `LAST_INSERT_ID` 在 INSERT 分支被自增列覆盖 | `nextCodeSequence` 改用 `ROW_COUNT()` 区分分支；单连接实测返回 1/2/3 |
| 前端构建 | `npm ci && npm run build` | `npm run build`（未重装依赖） | WSL 无 `npm ci` 所需网络条件，且 `node_modules` 未变 | 产物正常；`vue-tsc` 0 错误、`vitest` 92 文件全过 |
| 门禁 | 32 步整轮 | **已整轮执行：30 PASS / 2 FAIL**（13 浏览器回归=CDP 取消；06 lint=`check_tables` 存量债） | 首轮收口只跑分项机检；后按大师 D3 拍板补跑整轮 | 日志 `docs/evidence/opt-20261111/integration/gates-20261111-1642.log`；5 条 L7 用例由 NOT-RUN 转 PARTIAL，仍未宣称「门禁全绿」 |
| 内置模板内容 | 计划未列（原判「属产品运营数据」） | 新增 `V11_44` 为模板 2/3/4 补真实内容（20/20/18 条） | 大师 D1 拍板「用现存数据造内置模板内容」，否则「从模板建项目」无实质产物可验收 | 模板 1「空白项目」按设计保持空；`FR-v4.10-18/19/20/21` 转达成 |
| 从模板建项目复制链路 | 计划假定已可用（v4.9.2 交付） | **实测 500，修复后可用** | `copyCase` 的 INSERT 引用已重命名的 `category` 列；模板内容此前全空，该分支从未被执行 | 改 `INSERT ... SELECT` 整列搬迁，并补五列 + 两级分类；新增 `ProjectTemplateCopyIT`（真实 `robot_it`）锁死 |
| 文档单源 | `docs/plan`（基线）与 `docs/release`（归档）并存 | `docs/plan` 4 份已删除，release 为唯一源 | 大师 D4 拍板「合并为 release 单源」 | 删除前核对 13 个二级标题 100% 覆盖、593 行 ≥ 原 582 行；`tar` 留档 `docs/evidence/opt-20261111/plan-baseline-20261111.tar.gz` |

