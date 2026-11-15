## 迁移前孤儿盘点（V11_66 建外键前的存量检查）

```sql
SELECT 'user_group_member' AS tbl, COUNT(*) FROM user_group_member m
  LEFT JOIN app_user u ON u.id=m.user_id LEFT JOIN user_group g ON g.id=m.group_id
 WHERE u.id IS NULL OR g.id IS NULL;
SELECT 'project_group_role' AS tbl, COUNT(*) FROM project_group_role r
  LEFT JOIN project p ON p.id=r.project_id LEFT JOIN user_group g ON g.id=r.group_id
 WHERE p.id IS NULL OR g.id IS NULL;
```

```
user_group_member_orphans	0
project_group_role_orphans	0
project_rows	14
project_created_by_null	14
```

## 迁移后复查

```
user_group_member_orphans	0
project_group_role_orphans	0
app_user_is_demo_nonzero	0
project_is_demo_nonzero	0
project_created_by_null	13
fk_count	4
```
