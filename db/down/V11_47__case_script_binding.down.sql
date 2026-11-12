-- down for V11_47：撤销用例↔脚本绑定表（v5.1.0 · W51-02）。
-- 数据面：整表删除即回滚；绑定关系可由同步服务从仓库重扫重建，不需要保留数据。
-- 顺序：先删外键再删表，否则父表（project/test_case）上仍有引用。
ALTER TABLE `case_script_binding` DROP FOREIGN KEY `fk_binding_case`;
ALTER TABLE `case_script_binding` DROP FOREIGN KEY `fk_binding_project`;
DROP TABLE IF EXISTS `case_script_binding`;
