-- v5.22.0 / FR-v5.22-05 判据 05-1：`run` 表补 `skipped_tests`
--
-- 背景（F-22-18 ~ F-22-21，见 00-RESEARCH.md）：
-- `run` 表存在**两套并列的计数列**，各自只在半条链路上被写：
--   * `total_tests` / `passed_tests` / `failed_tests`（V4_1 加，pytest 执行期写）
--     —— pytest 路径写的是 `passed=0` 的估算值，robot 路径连 `failed_tests` 也不写；
--   * `pass_count` / `fail_count` / `skip_count`（V5_1 加，报告入库写）
--     —— 只有走过 `/api/reports/ingest` 的 run 才有真值。
-- 结果是同一个 run 在「执行页」与「报告页」显示两套数字（L-19-06 复现：
-- 明细 5 PASS / 4 FAIL / 61 SKIP，run 表读作 70 / 0 / 4）。
-- 并且旧三列**没有 SKIP 位置**，`passed + failed` 永远凑不出 `total`。
--
-- 本迁移的口径（与 PRD FR-05 一致）：
-- 1. **只追加一列**，不改任何既有列类型/语义、不动历史脚本；
-- 2. `skipped_tests` 允许 NULL —— NULL 的语义是「尚未回填」，与「真的是 0」区分开；
--    回填脚本（scripts/db/backfill-run-counts.sh）负责把有明细的 run 全部落定，
--    没有明细的 run 保持 NULL，不伪造 0（伪造后无法再区分「没跑过」与「跑了 0 条」）；
-- 3. 四列自此满足 `total_tests = passed_tests + failed_tests + skipped_tests`，
--    `verdict='DISPATCHED'`（已下发未落定）**不计入任何一列**，单列可查。

ALTER TABLE `run`
  ADD COLUMN `skipped_tests` INT NULL
      COMMENT 'SKIP 用例数；NULL=尚未回填（由 test_result 聚合回填）' AFTER `failed_tests`;
