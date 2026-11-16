# W67-04 ~ W67-07 向导接线与快照取证（v5.17.0）

> 对应：`FR-v5.17-01`（W67-04/05）、`FR-v5.17-02`（W67-06/07）；`TC-v5.17-01 ~ 12`。
> 取证时间：2026-11-16 08:20 ~ 08:50（GMT+8）。HEAD 基线 `876da9b` + 本版改动。

---

## 1. 结论

| 判据 | 结果 | 证据 |
|---|---|---|
| 01-1 卡片来自 `GET /api/global/templates` | **PASS** | 组件断言：列表 mock 生效，静态「空白项目」不出现 |
| 01-2 pytest 模板 → 第 3 步 pytest | **PASS** | 组件断言：radio `value=pytest` 被选中 |
| 01-3 卡片 `itemCount` = 详情四类之和 | **PASS** | 组件断言：卡片「内容 5 项」+ 摘要 2+1+1+1=5 |
| 01-4 空列表 → 空态 + 新建入口 | **PASS** | `templates-empty` / `templates-empty-create` 渲染 |
| 01-5 后端不可用 → 静态卡 + 离线标识 | **PASS** | `templates-offline` + 卡片 `data-offline="true"` |
| 02-1 场景分区可选可计数 | **PASS** | 4 条组件断言（见 §3） |
| 02-2 2 集 + 5 例 + 2 场景 = 9 项 | **PASS** | 复核步 `review-content-total` 含「9」 |
| 02-3 场景 id 非法 → 400 点名 | **PASS（口径修正，见 §4）** | `场景不存在：987654321` |
| 02-4 原子快照：源集 +2 → 项目成员不变 | **PASS** | 源集 5→7，项目成员仍 5 |
| 02-5 两向导共用同一组件 | **PASS** | `TemplateWizardView` 与 `ProjectWizardView` 同引 `WizardContentPicker` |
| 07 造数可逆 | **PASS** | 收尾后 9 张表回到基线 |

前端用例：`ProjectWizardView.spec.ts` **27 passed**、`WizardContentPicker.spec.ts` **4 passed**（新建）。

---

## 2. W67-04/05 改了什么

| 项 | 改动前 | 改动后 |
|---|---|---|
| 卡片数据源 | 前端静态 `PROJECT_TEMPLATES`（4 个写死的模板） | `templateApi.list()`，库里新建的模板立刻可见 |
| 内核来源 | 静态模板的 `testKernel` | 列表 `kernel`，选中后再用 `templateApi.get(id)` 的**详情**覆盖（详情才是权威） |
| 计数 | 无 | 卡片显示 `itemCount`；选中后显示详情四类计数（集/例/场景/资产） |
| 后端不可用 | 无兜底 | 回退静态卡并标「离线模板」，`data-offline="true"` |
| 空列表 | 空白区域 | 空态 + 「去新建模板」（跳 `/g/templates/new`） |

顺带修一处既有缺陷：`projectWizard.steps.groups` 缺 i18n 键，有管理权限的用户在步骤条上会看到字面 key。

---

## 3. W67-06 的关键判断：场景不在 `test_set` 里

PRD 原写「场景 = `test_set.kind='scenario'`」，依据是 `api/cases.ts:351` 的注释。**实测推翻了这条**：

| 事实 | 实测值 |
|---|---|
| `test_set.kind` 取值 | **全部 47 行都是 `case_set`**，没有一行是 `scenario` |
| 场景真实载体 | **`test_scenario` 表，41 行**（列 `case_set_ids` 存它引用的用例集 id 串） |
| id 空间 | 两张表各自自增，**id 有重叠**（如 32 在两边都存在） |

所以 D-4「复用 `caseSetIds` 承载场景 id」**技术上不成立**：混进去会按 id 撞到无关记录。
本版改为在 `ContentRequest` 增加可选 `scenarioIds`（向后兼容，旧调用语义不变），
后端只按 `test_scenario` 查、按表校验。

### 场景导入的落库口径

新项目里的场景是**重建**的：名字沿用源场景，`case_set_ids` 指向本轮新建的用例集
（与模板 `SCENARIO` 展开同一口径）。本轮没有新建用例集时留空串，不伪造引用。

---

## 4. 判据 02-3 的口径修正（必须登记）

原判据：「误把 `case_set` 标为场景 → 400 点名 kind 不符」。

实测暴露的真相：**因为两张表 id 重叠，后端无法靠 id 判断"这是不是用例集"**。
拿 `test_set.id=32` 传进 `scenarioIds`，`test_scenario` 里恰好也有 id=32 → 返回 200 并导入了**另一个**场景。
这是静默错关联，比报错更危险。

能靠服务端拦住的是「id 在 `test_scenario` 里不存在」，实测已验证：

```
POST /api/projects/130/content {"scenarioIds":[987654321]}
-> 400 场景不存在：987654321（scenarioIds 只接受 test_scenario 的 id，不能传用例集 id）
```

**结论**：判据改为「不存在的场景 id → 400 点名」；「误标」这一类防护落在**前端数据源**上
（场景候选只来自模板 `SCENARIO` 明细的 `refId`，类型上不可能混入用例集 id），并登记 id 重叠风险 `L-67-06`。

不做假声称：服务端目前**没有**能力区分"恰好撞号的用例集 id"。

---

## 5. W67-07 快照语义（实测）

```
[1] 源用例集 id=71 成员=5
[2] 建项目（带 caseSetIds=[71]）http=200 id=137
[3] 建项目后成员用例数 = 5
[4] 源集成员 5 -> 7（人为 +2）
[5] 项目成员用例数 = 5
    SNAPSHOT_STABLE=PASS（源集变了，项目没变）
    NOT_FOLLOWING=PASS
```

口径：`applyContent` 是**复制用例**到新项目（新 `test_case` 行），不是引用。所以源集后续变化天然不影响已建项目——
本组断言的价值是把这个语义钉住，防止以后改成"引用"而无人发现。

---

## 6. 清场时踩到的外键顺序（对 5.18 直接可用）

清理 W67-07 造数时第一轮失败，原因记在这里，避免 5.18 重犯：

| 表 | 引用列 | 备注 |
|---|---|---|
| `test_case_step` | **`test_case_id`**（不是 `case_id`） | 第一轮按 `case_id` 删 → 1451 |
| `case_requirement` / `case_requirement_version` / `case_script_binding` / `manual_execution` | `test_case_id` / `case_id` | 都可能拦 |
| `test_set_item` | `test_case_id` / `test_set_id` | 两侧都要清 |
| `execution_plan` / `run` | `test_set_id` | 删用例集前要先处理 |

正确顺序：`test_case_step → case_requirement* → case_script_binding → manual_execution → test_set_item → test_case → test_set → 项目关系表 → project`。

清理后基线（逐表）：`app_user 9 / project 14 / test_set 47 / test_case 12491 / run 546 /
test_result 725 / test_report 3 / device 21 / execution_node 9`。

---

## 7. 复跑命令

```bash
set -a; . ~/.config/robot-autotest/env; set +a
cd ~/robot-autotest/frontend
npx vitest run src/views/ProjectWizardView.spec.ts src/components/WizardContentPicker.spec.ts
npx vue-tsc --noEmit

# 真实链路（项目创建与场景校验）
python3 <W67-06 验证脚本>    # /api/projects/composite 与 /content
python3 <W67-07 验证脚本>    # 快照不变
```

> 注意：建项目的复合端点是 **`POST /api/projects/composite`**；`POST /api/projects` 只收 6 个基础字段，
> 传内容字段会 500（`UnrecognizedPropertyException`）。这是本次踩过的坑。
