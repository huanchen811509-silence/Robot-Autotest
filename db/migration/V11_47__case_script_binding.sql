-- v5.1.0 · W51-02 · 用例 ↔ 脚本绑定表（FR-v5.1-04 / 验收 A8；ADR-51-02）。
--
-- 为什么必须有这张表：
--   junit 的 `<testcase classname="tests.nic.base.test_im_fun_020" name="test_...">`
--   是**点分**形态，json-report 的 `nodeid` 是**斜杠 + :: ** 形态，两者不同源；
--   而平台对外要显示的是用例库编号 `IM-FUN-020`。任何「从 nodeid 字符串反解编号」
--   的做法都会在参数化、同名文件、目录调整时出错（ADR-51-02 明确禁止）。
--
--   因此绑定关系必须**显式落库**，由同步服务扫描 git 工作区生成，
--   `resolve_source` 记录这一条是怎么来的，便于审计与排错：
--     FILENAME  文件名反推（test_im_net_131.py → IM-NET-131，降级手段，覆盖率高但可能误绑）
--     DOCSTRING 文件头 `CASE_ID: IM-NET-131` 声明（框架规范推荐，唯一可信）
--     MANUAL    人工在界面上指定（用于文件名与编号无对应关系的历史脚本）
--
-- 唯一性：一条用例在一个内核下只能有一个绑定（UNIQUE 前 3 列）。
--   参数化用例的多个实例共用同一条绑定——匹配走 `node_id` 前缀，不逐实例建行。
--
-- 表数 +1（98 → 99 业务表），`db/schema-contract.json` 同步更新。

CREATE TABLE IF NOT EXISTS `case_script_binding` (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  project_id     BIGINT       NOT NULL,
  case_id        BIGINT       NOT NULL COMMENT 'test_case.id',
  kernel         VARCHAR(32)  NOT NULL DEFAULT 'pytest' COMMENT 'pytest/robot',
  script_path    VARCHAR(512) NOT NULL COMMENT '相对仓库根的脚本路径',
  node_id        VARCHAR(512) NULL     COMMENT 'pytest 基础 nodeid（不含 [param]）；robot 为空',
  script_sha256  CHAR(64)     NULL     COMMENT '脚本内容哈希，用于重命名跟随与漂移检测',
  resolve_source VARCHAR(16)  NOT NULL DEFAULT 'FILENAME' COMMENT 'FILENAME/DOCSTRING/MANUAL',
  verified       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否经人工确认（MANUAL 或已复核）',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by     BIGINT       NULL,
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by     BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_binding_case_kernel (project_id, case_id, kernel),
  KEY idx_binding_project_path (project_id, script_path),
  KEY idx_binding_node (project_id, kernel, node_id),
  KEY idx_binding_sha (project_id, script_sha256),
  CONSTRAINT fk_binding_project FOREIGN KEY (project_id) REFERENCES `project` (id),
  CONSTRAINT fk_binding_case FOREIGN KEY (case_id) REFERENCES `test_case` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用例与脚本/内核节点绑定';
