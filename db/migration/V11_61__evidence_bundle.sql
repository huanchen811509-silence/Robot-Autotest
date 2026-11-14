-- V11_61 证据包指针表（v5.7.0 / FR-v5.7-14 / W57-04）
--
-- 借鉴白泽：文件本身内容寻址（文件名=sha256），DB 只存「这一批证据的指针摘要」。
-- 指针表不是文件清单的唯一真源 —— 磁盘上的 pointer 文件才是；DB 这行只用于
-- 列表展示、状态追踪与快速对账，二者不一致时以「读时重算」为准。
--
-- 口径：uk(bundle_key) 保证一批证据只有一个指针；status 三态
-- building（临时目录写入中）/ ready（原子发布完成）/ failed（写入失败，可重试）。
-- 审计四列齐全且非 append-only（status 会更新），满足 db/schema-contract.json 体检。

CREATE TABLE `evidence_bundle` (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  project_id    BIGINT       NULL COMMENT '平台级证据包为 NULL',
  bundle_key    VARCHAR(128) NOT NULL COMMENT '业务键，如 report:<runId> / editor:<id>',
  pointer_sha   CHAR(64)     NOT NULL COMMENT '指针文件内容的 sha256',
  file_count    INT          NOT NULL DEFAULT 0,
  total_bytes   BIGINT       NOT NULL DEFAULT 0,
  status        VARCHAR(16)  NOT NULL DEFAULT 'building' COMMENT 'building/ready/failed',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by    BIGINT       NULL,
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by    BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY `uk_evidence_bundle_key` (`bundle_key`),
  KEY `idx_evidence_bundle_project` (`project_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证据包指针（v5.7.0）';
