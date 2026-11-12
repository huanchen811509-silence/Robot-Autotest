-- V11_54 时长基线补 P99 分位（W53-03 / FR-v5.3-03 R1）
--
-- 为什么是补列而不是改 V11_51：
-- V11_51 已在 robot_dev / robot_it 应用过，Flyway 会校验 checksum，改已应用的迁移会
-- 让所有环境在下次启动时直接失败（checksum mismatch）。因此只能追加。
--
-- 为什么要 P99 而不是「有 P90 就够了」：
-- 验收判据是「分位单调 p50 ≤ p90 ≤ p99 ≤ max」。只有 P90 与 max 时，中间那段
-- 「尾部到底有多长」是看不见的——2 条离群样本能把 max 拉到 P90 的 20 倍，
-- 而 P99 会如实告诉你「99% 的样本都在这个值以内」。软超时阈值取 P90×1.5，
-- P99 的作用是**校验这个阈值是否已经贴近真实尾部**：若 P99 远大于 P90×1.5，
-- 说明尾部很长，阈值偏紧，这条用例会频繁误报 soft-timeout。
--
-- 可空：老数据重算前没有 P99 值，NULL 表示「未计算」，与 0 区分。

ALTER TABLE `case_duration_baseline`
  ADD COLUMN `p99_ms` BIGINT NULL
    COMMENT 'P99：用于校验 P90×1.5 是否贴近真实尾部（NULL=未计算）'
    AFTER `p90_ms`;
