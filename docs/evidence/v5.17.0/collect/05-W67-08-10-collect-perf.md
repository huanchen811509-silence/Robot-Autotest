# W67-08 ~ W67-11 采集凭证名与 500 台画像（v5.17.0）

> 对应：`FR-v5.17-05`（W67-08/09）、`FR-v5.17-06`（W67-10/11）；`TC-v5.17-22 ~ 31`。
> 取证时间：2026-11-16 08:32 ~ 08:47（GMT+8）。

---

## 1. 结论

| 判据 | 结果 | 证据 |
|---|---|---|
| 05-1 凭证 A 采集 → 首行指向 A，掩码名 | **PASS** | 两行分别指向 cred 1/2：`nod***ot` / `nod***in` |
| 05-2 换凭证 B → 两条分别指向 | **PASS** | 按 `verified_at <= collected_at` 对齐，不是取"最近一条" |
| 05-3 无事实 → `未记录` | **PASS** | 组件断言 `w63.credentialNotRecorded` |
| 05-4 零泄漏 | **PASS** | `probe_oneshot_secret` **6/6 PASS**；响应无 `secretRef`/`secret_ref` |
| 05-5 反向回放：`876da9b` 无该字段 | **PASS** | 旧版 grep 命中 **0**，工作树命中 1 |
| 06-1 500 台下 P95 ≤ 800ms | **PASS** | **P95 = 79.2ms**（10 次，500 行有效样本） |
| 06-3 既有工程/用例计数不变 | **PASS** | 非 PERF 项目 13→13，用例 2491→2491 |
| 06-4 清理后残留 0 | **PASS** | `PERF-PROFILE` 项目与 500 台设备均 0 |

---

## 2. W67-08：为什么"整列一个值"算缺陷

改前那一列渲染的是 `props.boundCredentialName`——**设备当前绑定的凭证**，于是历史列表里每一行都显示同一个名字。
换过凭证的用户看到的是"一直都用这个"，等于没有历史。

改后按行取「本次采集时刻之前最近一次验证的凭证」：

```sql
(SELECT cv.credential_id FROM credential_verification cv
  WHERE cv.device_id = h.device_id AND cv.verified_at <= h.collected_at
  ORDER BY cv.verified_at DESC, cv.id DESC LIMIT 1)
```

`verified_at <= h.collected_at` 这个条件是关键：只按 device 取最新一条，会让所有历史行都指向**新**凭证，
历史同样失效。

名字在应用层掩码（`maskName`）：长度 ≤2 全遮，≤5 留首尾，否则前 3 + 后 2。
实测 `node-ssh-root` → `nod***ot`、`node-bmc-admin` → `nod***in`。

> 局限要写清：两个名字前缀恰好都是 `node-`，所以掩码后辨识度不高。掩码规则按"留首尾"实现，
> 不按语义分段——按语义分段需要理解命名规范，而规范本身没有强制。

---

## 3. W67-09 零泄漏与反向回放

```
[PASS] DB - 静态扫描：未发现材料写入 DB | mock：自检有效且哨兵零出现
[PASS] APP_LOG ... [PASS] AUDIT ... [PASS] HTTP_RESPONSE ...
[PASS] EVIDENCE_BUNDLE ... [PASS] EXCEPTION_STACK ...
PROBE_RESULT PASS=6/6
```

反向回放用代码级对比（真跑旧版后端成本过高）：

| 检查 | `876da9b` | 工作树 |
|---|---|---|
| `credentialMaskedName` 出现次数 | **0** | 1 |
| `JOIN credential c` 出现次数 | **0** | 1 |

---

## 4. W67-10：500 台画像压测

| 项 | 值 |
|---|---|
| 样本 | 10 次 `GET /api/assets/collect-profiles?projectId=135` |
| 行数 | 每次 500 行（`SAMPLE_VALID=PASS`） |
| 读数 | min 54.2 / median 60.5 / **P95 79.2** ms |
| 判据 | ≤800ms → **PASS**，余量约 10 倍 |
| 隔离 | 独立项目 `PERF-PROFILE`，500 台 `PERF-DEV-*` |

**W67-11 不触发**：P95 79.2ms 远低于 800ms 阈值，按计划「未触发则记不执行并附读数」。
不为了"做得更多"去改一段没有性能问题的 SQL。

### 压测脚本的两个坑（记下来）

1. `tools/perf_seed.py` **没有** `--devices 500` 参数（DEV-PLAN 里那条命令不存在），本次改为自建造数脚本。
2. `device` 表的 `status` 是 `tinyint`、`asset_status` 是 `varchar`、默认值分别是 `1` / `active`；
   硬塞 `'active'` 到 `status` 会 1366。最稳的写法是**只给必填列**（`project_id` + `name`），其余吃默认值。

---

## 5. 复跑命令

```bash
set -a; . ~/.config/robot-autotest/env; set +a
cd ~/robot-autotest
python3 tools/probe_oneshot_secret.py                     # 6/6
python3 <W67-08 验证脚本>                                  # 两次凭证分别指向
python3 <W67-10 压测脚本>                                  # P95 读数 + 清理
cd frontend && npx vitest run src/components/CollectHistoryPanel.spec.ts
```
