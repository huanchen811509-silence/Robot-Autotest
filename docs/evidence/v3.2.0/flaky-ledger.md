# v3.2.0 Flaky 台账

日期：2026-11-08 · 维护人：研发自测

## 1. 判定口径

- **失败先复跑**：同一命令连续 2 次失败才记为「稳定失败」，只失败 1 次记为「flaky 待观察」。
- **不得为了让测试变绿而改断言/跳过**：环境性失败必须写清根因、影响面与替代证据。
- **环境分离**：`mvn test` 以 WSL Ubuntu-24.04（Linux 换行、Python3、fake CLI 可用）为权威；
  Windows PowerShell 直跑仅作参考，不作为发布判据。

## 2. 历史记录

| 首次日期 | 用例 | 现象 | 根因 | 处置 | 当前状态 |
|---|---|---|---|---|---|
| 2026-11-07 | `AiContextScrubTest`（1） | 断言失败 | Windows 缺 fake CLI，`taskkill`/路径差异 | WSL 下复跑通过 | 已消除 |
| 2026-11-07 | `AiProcessManagerStdinTest`（1） | 超时 | Windows 无 `sh`/stdin 换行差异 | WSL 下复跑通过 | 已消除 |
| 2026-11-07 | `AiRealCliChainTest`（4） | 启动失败 | fake CLI 脚本权限/路径 | WSL 下复跑通过 | 已消除 |
| 2026-11-07 | `AiSuggestContentTest`（3） | 返回空 | 依赖 fake CLI 输出 | WSL 下复跑通过 | 已消除 |
| 2026-11-07 | `EvidenceLogFormatTest`（2 errors） | 文件不存在 | Windows 临时目录/换行 | WSL 下复跑通过 | 已消除 |
| 2026-11-07 | `TestTimeoutInjectorTest`（1） | 超时不触发 | Windows 进程树 API 差异 | WSL 下复跑通过 | 已消除 |
| 2026-11-07 | `PytestRunAdapterTest`（1） | 找不到 python | Windows PATH 无 python3 | WSL 下复跑通过 | 已消除 |

## 3. 当前基线（2026-11-08）

```
Tests run: 1211, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

- 权威日志：`docs/evidence/v3.2.0/EW32-05/logs/backend-full-test.log`
- 前端：`72 文件 / 490 例` 全绿（`EW32-06/logs/frontend-tests.log`）
- 编辑器：单独复跑，全绿（见 EW32-06 构建日志与最终收口记录）
- 结论：**本版本无未解释的 flaky**；Windows 直跑出现的 14 条已全部归因为环境差异，
  在 WSL 权威环境 0 复现。
- 门禁 13（2026-11-08）：WSL 无 Chrome，`127.0.0.1:9222` 不可达 → `fetch failed`；
  用 Chrome for Testing headless shell 157 托管 CDP 后复跑，48 项全 PASS。
  另修正门禁脚本过期期望：根路由默认落地页 v3.1 起为 `/workbench`（原写 `/overview`）。
  处置未改断言、未跳过步骤，当前 0 复现。

## 3.1 2026-11-09 新增记录（v3.5+ B1 门禁）

| 首次日期 | 用例 | 现象 | 根因 | 处置 | 当前状态 |
|---|---|---|---|---|---|
| 2026-11-09 | `frontend/src/views/AdminView.spec.ts > 渲染用户行，空邮箱显示统一占位` | 27 步全量门禁第 02 步 `Test timed out in 5000ms`，同批 492/493 通过 | 73 个 spec 文件并发 collect（日志 collect 111.99s）时该用例的 `mountView()` 被调度拖过 5s 默认边界；**非断言失败、非产品缺陷** | ① 按 §4 连续 2 次单文件复跑：`npx vitest run src/views/AdminView.spec.ts` 两次均 `8 passed (8)`，该用例 598ms；② 在 `frontend/vite.config.ts` 的 test 段设 `testTimeout=10000` / `hookTimeout=10000`，**只放宽超时余量，未改断言、未删用例、未跳过步骤**；③ 重跑 27 步全量门禁，02 与 19 同步转绿 | 已消除（待同批次汇总确认） |

**连锁影响说明**：第 19 步 `check_test_baseline.py` 从 `02-frontend_test.log` 解析 `Tests N passed (N)`；
02 出现 1 条失败时日志格式变为 `Tests 1 failed | 492 passed (493)`，解析器取不到有效读数而判红。
因此 19 的 FAIL 是 02 的**下游连锁**，不是独立的基线倒退；修 02 后须用同批次汇总复核两处。

## 4. 复跑策略

1. 单元/契约测试：`mvn -Dtest=<Class> test` 连续 2 次；
2. 前端：`npm run test -- <file>` 连续 2 次；
3. 仍失败则提 issue，附两次日志与最小复现命令，**不带病发布**。
