-- V11_57 失败重试与抖动标记（FR-v5.6-24 / W56-24）
--
-- 两条设计取舍，都直接影响「这个数字还能不能对外说」：
--
-- 1) retry_count 记的是「这一次最终结论之前实际又跑了几轮」，不是「配置允许几轮」。
--    配置值是执行请求参数，事后会改；把配置值写进历史结果，等于让历史结论跟着
--    配置漂移。同一个字段名（retry）在请求里是意图、在结果里是事实，必须分开。
--
-- 2) flaky 是判定结果，不是次数。只存 retry_count 会让每个消费方自己推断
--    「跑过 2 轮且末轮 PASS 算不算抖动」——前端推断一次、报表推断一次、
--    对外口径再推断一次，三处规则一旦不同，同一批数据会得出三个抖动数。
--
-- 3) 两者都带 NOT NULL DEFAULT 0：历史行（本版之前）天然是「未复测、非抖动」，
--    与事实一致，不需要额外回填 SQL。

ALTER TABLE `test_result`
  ADD COLUMN `retry_count` INT NOT NULL DEFAULT 0
    COMMENT '复测轮数：首轮之外实际又跑了几轮，0 表示未复测'
    AFTER `round_no`,
  ADD COLUMN `flaky` TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '抖动标记（首轮失败、复测通过）：1 时不计入对外失败数'
    AFTER `verdict`;
