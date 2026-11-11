-- OPT-20261111 / 遗留缺陷 L-49-01：补齐 schema 审计口径（门禁第 12 步 db_audit）。
-- 背景：V11_30 / V11_33 / V11_34 / V11_36 / V11_37 建了 8 张表，其中 7 张缺
-- created_at / created_by / updated_at / updated_by 中的若干列，2 张主键不是单列 id，
-- 导致 scripts/db-audit.sh 与 SchemaIT 自 v4.9.0 起长期判红（本批只 ALTER 未建表，
-- 业务表数仍为 98，故与 OPT 功能改动无关，属历史欠账）。
--
-- 处置口径：
--   1) case_version 是真 append-only（全代码库无一条 UPDATE，版本快照不可变），
--      走 db/schema-contract.json 的 appendOnlyTables 豁免，**不补 updated_*** ，
--      避免逼业务代码写假值（这是契约 notes 里的既有原则）。
--   2) 其余 7 张表均存在 UPDATE 或 delete+insert 语义，按仓库统一约定补齐四列。
--   3) project_code_sequence（PK=scope）与 project_member_role（联合主键）改为
--      「代理 id 主键 + 原主键降级为唯一键」，保持原有唯一性语义与
--      INSERT ... ON DUPLICATE KEY / INSERT IGNORE 行为不变；两表均无外来外键引用，
--      且 project_member_role 的两个外键由新唯一键最左列 user_id/project_id 继续支撑。
-- 施工顺序按「先加唯一键、再换主键」拆两步，避免 MySQL 报
-- "Cannot drop index 'PRIMARY': needed in a foreign key constraint"。

-- ---------- 1) 纯补列（无主键变更） ----------
ALTER TABLE `import_item`
  ADD COLUMN `created_by` BIGINT NULL COMMENT '导入人 app_user.id；历史行保持 NULL',
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '回填 case_id 时刷新',
  ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后修改人 app_user.id';

ALTER TABLE `project_template_item`
  ADD COLUMN `created_by` BIGINT NULL COMMENT '模板内容维护人 app_user.id',
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '模板内容清单最后变更时间',
  ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后修改人 app_user.id';

ALTER TABLE `project_template_asset`
  ADD COLUMN `created_by` BIGINT NULL COMMENT '模板资产引用维护人 app_user.id',
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '模板资产引用最后变更时间',
  ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后修改人 app_user.id';

ALTER TABLE `sync_job`
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '状态机推进时间',
  ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后修改人 app_user.id';

ALTER TABLE `sync_job_item`
  ADD COLUMN `created_by` BIGINT NULL COMMENT '发起同步人 app_user.id',
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'local_id 回填时间',
  ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后修改人 app_user.id';

-- ---------- 2) project_code_sequence：代理主键化 ----------
ALTER TABLE `project_code_sequence`
  ADD COLUMN `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      COMMENT '该 scope 首次分配时间',
  ADD COLUMN `created_by` BIGINT NULL COMMENT '首个人工触发者；系统分配为 NULL',
  ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后修改人 app_user.id',
  ADD UNIQUE KEY `uk_project_code_sequence_scope` (`scope`);

ALTER TABLE `project_code_sequence`
  DROP PRIMARY KEY,
  ADD COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '代理主键（v4.4.0 起 PK 为 scope，本次改为 id）' FIRST,
  ADD PRIMARY KEY (`id`);

-- ---------- 3) project_member_role：代理主键化 ----------
ALTER TABLE `project_member_role`
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '角色最后授予/回收时间',
  ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后修改人 app_user.id',
  ADD UNIQUE KEY `uk_project_member_role_unique` (`project_id`,`user_id`,`role_code`);

ALTER TABLE `project_member_role`
  DROP PRIMARY KEY,
  ADD COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '代理主键（原联合主键降级为唯一键）' FIRST,
  ADD PRIMARY KEY (`id`);
