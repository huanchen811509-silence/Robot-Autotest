-- V11_57 回滚：摘掉重试与抖动两列
--
-- 数据的后果：所有「哪几条用例是抖动」的判定都会丢失，且不可恢复——
-- 原始 junit 里还留着首轮与复测两份产物，但「这两份属于同一次运行的第几轮」
-- 只由这两列承载，产物本身不带轮次信息。回滚前应先导出：
--   SELECT run_id, case_key, verdict, retry_count, flaky FROM test_result WHERE retry_count > 0;
--
-- 注意：回滚后旧的归一器仍会照常归一出首轮结论，不会报错，只是不再标抖动。
-- 也就是说这不是「坏掉」，而是「静默降级」——正因如此才要在这里写明。

ALTER TABLE `test_result`
  DROP COLUMN `flaky`,
  DROP COLUMN `retry_count`;
