-- v5.9.0 / W59-02 回滚：先解外键（数据不恢复——被级联删掉的关联行无法从结构回滚）
ALTER TABLE `project_group_role`
  DROP FOREIGN KEY `fk_pgr_group`,
  DROP FOREIGN KEY `fk_pgr_project`;

ALTER TABLE `user_group_member`
  DROP FOREIGN KEY `fk_ugm_group`,
  DROP FOREIGN KEY `fk_ugm_user`;
