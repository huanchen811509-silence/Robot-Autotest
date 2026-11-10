# EW41-02 · 项目导出（FR-v4.1-01）

| 项 | 内容 |
|---|---|
| 工作包 | `W41-02` 项目导出（含凭据白名单） |
| 日期 | 2026-11-09 |
| 环境 | 后端 `:8081`（4.2.0 包）· 真库 `robot_dev` · 项目 29 `qa-e2e-fixture` · qa-e2e-admin / viewer / nobody |
| 结论 | `RESULT PASS passes=16 fails=0` |

---

## 1. 真实读数（`bash scripts/workspace-contract.sh export`）

| 组 | 断言 | 读数 |
|---|---|---|
| E1 | 导出 PROJECT 返回 200 | `PASS`；包路径 = 项目 `code`（`qa-e2e-fixture`）；报告文件数 24 |
| E2 | 契约结构齐全 | 目录/文件齐全；`granularity=project`、`rootPath=null` |
| E2 | 签名 | `keyId=server-local-v1`；包结构 + HMAC 机检通过（与 `tools/check_workspace_bundle.py --bundle` 同口径） |
| E3 | 篡改必拒 | 改一字节 → 预检 400 `INVALID_ARGUMENT`；改签名 → 预检 400 `INVALID_ARGUMENT` |
| E4 | 越界与失败清理 | 越界 `rootPath` → 400；失败任务落 `FAILED`；旧包未被破坏；无残留临时/回收目录；失败审计留痕 |
| E5 | 权限矩阵 | viewer 可列包（`CASE:READ`）；非成员导出 403 |

证据：`workspace-contract-export.log`（16 项 PASS）。

## 2. 实现口径

- 导出以项目 code 为目录名，写 `project.json / members.json / README.txt / manifest.json` 与 7 个业务子目录；
- manifest 逐文件 `sha256 + bytes`，整体 `HMAC-SHA256`（`keyId=server-local-v1`）；
- 写失败一律清理临时目录、保留旧包，并把失败写审计与任务状态；
- 凭据只写掩码指纹（如 `a1b2c3d4e5f6`），明文口令/令牌命中即被机检判红。

## 3. 判不了的部分（NOT-RUN）

| # | 项 | 原因 | 补齐 |
|---|---|---|---|
| 1 | 读事务快照 / 项目锁 | 契约脚本未做并发写库注入，无法证明快照隔离与锁等待 | 需两路并发导出 + 同时改库的注入用例 |
| 2 | 配额负向 | 未构造超大包触发配额拒绝 | 需配额调小后的负向样本 |
| 3 | 依赖闭包告警 | 导出不检查用例→资产引用闭包（由导入侧与 levels 侧覆盖） | 见 EW41-04 |
