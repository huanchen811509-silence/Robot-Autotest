-- V11_59 执行结果判据列（v5.7.0 / FR-v5.7-12、13 / W57-02）
--
-- 白泽调研的核心结论：status 是事实，conclusion 是结论，二者不能互相代替。
-- pytest 退出 0 只说明「进程跑完了」，不等于「这条用例达标」；结论必须由冻结判据逐条裁定。
--
-- 口径：
-- 1) 三列全可空。NULL = 旧数据 / 未声明判据，界面显示「未声明」，**不得**默认判 PASS；
-- 2) criteria_json 存快照而非指针，判据在用例侧被改掉不会改写历史结论；
-- 3) criteria_missing 记录「缺哪个指标」，这样 MISSING 是可解释的而不是一个空结论；
-- 4) 只在 test_result 加列，不动 run / test_case —— 判据是「这一次执行」的事实。

ALTER TABLE `test_result`
  ADD COLUMN `criteria_json` MEDIUMTEXT NULL
      COMMENT '冻结判据快照 JSON（含阈值与实测值）' AFTER `failure_msg`,
  ADD COLUMN `criteria_verdict` VARCHAR(16) NULL
      COMMENT 'PASS/FAIL/MISSING；NULL=未声明判据' AFTER `criteria_json`,
  ADD COLUMN `criteria_missing` TEXT NULL
      COMMENT '缺失指标清单与原因，便于解释 MISSING' AFTER `criteria_verdict`,
  ADD KEY `idx_test_result_criteria` (`run_id`, `criteria_verdict`);
