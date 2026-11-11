-- OPT-20261111 / 遗留缺陷 L-49-02：四个内置模板内容全为空，导致
-- POST /api/projects/from-template 只能建出空项目，FR-v4.10-18/19/20 的实质产物无法验收。
--
-- 处置口径（2026-11-11 大师拍板 D1「用现存数据造内置模板内容」）：
--   1) 内容全部取自**真实存量数据**：用例来自项目 88（730 条台账导入，含 A端/B端/交换机/
--      测试结果/测试资源五列补录），资产引用来自 device / credential 实表，不虚构。
--   2) 「空白项目」模板**故意保持空**——它的产品语义就是"从零开始"，
--      往里塞内容会破坏"新建即空白"的预期。
--   3) 三个有语义的模板按**分类对齐**（不是随便抓几条）：
--        接口回归（API）  → 以太网功能测试 12 + 基础组网与链路 8（本域"接口"=网口/链路）
--        Web UI 巡检（UI） → 云平台接入测试 12 + 云资源生命周期测试 8（管理面操作类）
--        Pytest 服务（PY） → 基础管理测试 10 + 硬件与认证测试 8（可断言的信息查询类）
--   4) mode 一律 copy：建项目时复制用例正文并重算 content_sha，
--      改公共库不影响项目副本（ProjectTemplateService#generateContent 语义）。
--   5) 场景项只写名称（snapshot_json）：当前生成逻辑只建场景壳、不写 case_set_ids
--      （见遗留缺陷 L-49-07），不在此处伪装成"已编排"。
--   6) 种子行 created_by / updated_by 留 NULL 作为**种子标记**，回滚脚本据此精确删除，
--      不会误删用户后来手工添加的模板内容。
--
-- 空库（SchemaIT 从零迁移）下 test_case / device 无数据，各 INSERT ... SELECT 命中 0 行，
-- 不报错、不留残行，故本迁移在全新库同样安全。

-- ---------- 模板 2「接口回归」----------

INSERT INTO `project_template_item`
    (template_id, item_type, ref_id, mode, sort_no, snapshot_json, created_at, created_by, updated_at, updated_by)
SELECT 2, 'CASE', x.id, 'copy', x.rn, x.title, NOW(3), NULL, NOW(3), NULL
  FROM (SELECT id, title, ROW_NUMBER() OVER (ORDER BY id) AS rn
          FROM `test_case`
         WHERE project_id = 88 AND lifecycle_status = 'active'
           AND category_l1 = '以太网功能测试'
         ORDER BY id LIMIT 12) x;

INSERT INTO `project_template_item`
    (template_id, item_type, ref_id, mode, sort_no, snapshot_json, created_at, created_by, updated_at, updated_by)
SELECT 2, 'CASE', x.id, 'copy', 100 + x.rn, x.title, NOW(3), NULL, NOW(3), NULL
  FROM (SELECT id, title, ROW_NUMBER() OVER (ORDER BY id) AS rn
          FROM `test_case`
         WHERE project_id = 88 AND lifecycle_status = 'active'
           AND category_l1 = '基础组网与链路'
         ORDER BY id LIMIT 8) x;

INSERT INTO `project_template_item`
    (template_id, item_type, ref_id, mode, sort_no, snapshot_json, created_at, created_by, updated_at, updated_by)
SELECT 2, 'SCENARIO', NULL, 'copy', 900, '接口回归 · 冒烟与全量两轮', NOW(3), NULL, NOW(3), NULL
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `project_template_item`
                    WHERE template_id = 2 AND item_type = 'SCENARIO'
                      AND snapshot_json = '接口回归 · 冒烟与全量两轮');

INSERT INTO `project_template_asset`
    (template_id, asset_type, asset_id, mode, created_at, created_by, updated_at, updated_by)
SELECT 2, 'DEVICE', d.id, 'reference', NOW(3), NULL, NOW(3), NULL
  FROM `device` d
 WHERE d.id IN (1, 2)
   AND NOT EXISTS (SELECT 1 FROM `project_template_asset`
                    WHERE template_id = 2 AND asset_type = 'DEVICE' AND asset_id = d.id);

INSERT INTO `project_template_asset`
    (template_id, asset_type, asset_id, mode, created_at, created_by, updated_at, updated_by)
SELECT 2, 'CREDENTIAL', c.id, 'reference', NOW(3), NULL, NOW(3), NULL
  FROM `credential` c
 WHERE c.id = 1
   AND NOT EXISTS (SELECT 1 FROM `project_template_asset`
                    WHERE template_id = 2 AND asset_type = 'CREDENTIAL' AND asset_id = c.id);

-- ---------- 模板 3「Web UI 巡检」----------

INSERT INTO `project_template_item`
    (template_id, item_type, ref_id, mode, sort_no, snapshot_json, created_at, created_by, updated_at, updated_by)
SELECT 3, 'CASE', x.id, 'copy', x.rn, x.title, NOW(3), NULL, NOW(3), NULL
  FROM (SELECT id, title, ROW_NUMBER() OVER (ORDER BY id) AS rn
          FROM `test_case`
         WHERE project_id = 88 AND lifecycle_status = 'active'
           AND category_l1 = '云平台接入测试'
         ORDER BY id LIMIT 12) x;

INSERT INTO `project_template_item`
    (template_id, item_type, ref_id, mode, sort_no, snapshot_json, created_at, created_by, updated_at, updated_by)
SELECT 3, 'CASE', x.id, 'copy', 100 + x.rn, x.title, NOW(3), NULL, NOW(3), NULL
  FROM (SELECT id, title, ROW_NUMBER() OVER (ORDER BY id) AS rn
          FROM `test_case`
         WHERE project_id = 88 AND lifecycle_status = 'active'
           AND category_l1 = '云资源生命周期测试'
         ORDER BY id LIMIT 8) x;

INSERT INTO `project_template_item`
    (template_id, item_type, ref_id, mode, sort_no, snapshot_json, created_at, created_by, updated_at, updated_by)
SELECT 3, 'SCENARIO', NULL, 'copy', 900, 'Web UI 巡检 · 日常巡检', NOW(3), NULL, NOW(3), NULL
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `project_template_item`
                    WHERE template_id = 3 AND item_type = 'SCENARIO'
                      AND snapshot_json = 'Web UI 巡检 · 日常巡检');

INSERT INTO `project_template_asset`
    (template_id, asset_type, asset_id, mode, created_at, created_by, updated_at, updated_by)
SELECT 3, 'DEVICE', d.id, 'reference', NOW(3), NULL, NOW(3), NULL
  FROM `device` d
 WHERE d.id = 3
   AND NOT EXISTS (SELECT 1 FROM `project_template_asset`
                    WHERE template_id = 3 AND asset_type = 'DEVICE' AND asset_id = d.id);

INSERT INTO `project_template_asset`
    (template_id, asset_type, asset_id, mode, created_at, created_by, updated_at, updated_by)
SELECT 3, 'CREDENTIAL', c.id, 'reference', NOW(3), NULL, NOW(3), NULL
  FROM `credential` c
 WHERE c.id = 1
   AND NOT EXISTS (SELECT 1 FROM `project_template_asset`
                    WHERE template_id = 3 AND asset_type = 'CREDENTIAL' AND asset_id = c.id);

-- ---------- 模板 4「Pytest 服务」----------

INSERT INTO `project_template_item`
    (template_id, item_type, ref_id, mode, sort_no, snapshot_json, created_at, created_by, updated_at, updated_by)
SELECT 4, 'CASE', x.id, 'copy', x.rn, x.title, NOW(3), NULL, NOW(3), NULL
  FROM (SELECT id, title, ROW_NUMBER() OVER (ORDER BY id) AS rn
          FROM `test_case`
         WHERE project_id = 88 AND lifecycle_status = 'active'
           AND category_l1 = '基础管理测试'
         ORDER BY id LIMIT 10) x;

INSERT INTO `project_template_item`
    (template_id, item_type, ref_id, mode, sort_no, snapshot_json, created_at, created_by, updated_at, updated_by)
SELECT 4, 'CASE', x.id, 'copy', 100 + x.rn, x.title, NOW(3), NULL, NOW(3), NULL
  FROM (SELECT id, title, ROW_NUMBER() OVER (ORDER BY id) AS rn
          FROM `test_case`
         WHERE project_id = 88 AND lifecycle_status = 'active'
           AND category_l1 = '硬件与认证测试'
         ORDER BY id LIMIT 8) x;

INSERT INTO `project_template_item`
    (template_id, item_type, ref_id, mode, sort_no, snapshot_json, created_at, created_by, updated_at, updated_by)
SELECT 4, 'SCENARIO', NULL, 'copy', 900, 'Pytest 服务 · 健康检查', NOW(3), NULL, NOW(3), NULL
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `project_template_item`
                    WHERE template_id = 4 AND item_type = 'SCENARIO'
                      AND snapshot_json = 'Pytest 服务 · 健康检查');

INSERT INTO `project_template_asset`
    (template_id, asset_type, asset_id, mode, created_at, created_by, updated_at, updated_by)
SELECT 4, 'DEVICE', d.id, 'reference', NOW(3), NULL, NOW(3), NULL
  FROM `device` d
 WHERE d.id = 1
   AND NOT EXISTS (SELECT 1 FROM `project_template_asset`
                    WHERE template_id = 4 AND asset_type = 'DEVICE' AND asset_id = d.id);

INSERT INTO `project_template_asset`
    (template_id, asset_type, asset_id, mode, created_at, created_by, updated_at, updated_by)
SELECT 4, 'CREDENTIAL', c.id, 'reference', NOW(3), NULL, NOW(3), NULL
  FROM `credential` c
 WHERE c.id = 2
   AND NOT EXISTS (SELECT 1 FROM `project_template_asset`
                    WHERE template_id = 4 AND asset_type = 'CREDENTIAL' AND asset_id = c.id);
