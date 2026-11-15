#!/usr/bin/env bash
# v5.9.0 W59-06 真实链路取证：三源并集对比旧口径（只认 project_member）
set -u
set -a
. "$HOME/.config/robot-autotest/env"
set +a
export MYSQL_PWD="$DB_PASSWORD"
cd "$HOME/robot-autotest" || exit 1
mkdir -p docs/evidence/v5.9.0/sql

{
  echo "# v5.9.0 三源并集：真实库读数（W59-06 判据 1~5）"
  echo
  echo "库：\`$DB_NAME\`；命令见 \`docs/evidence/v5.9.0/sql/visibility-union.sh\`。"
  echo
  echo "## 1. 每个账号「我参与的项目」旧口径 vs 新口径"
  echo
  echo '```'
  mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" "$DB_NAME" -t -e "
  SELECT u.id AS user_id, u.username,
         (SELECT COUNT(*) FROM project p JOIN project_member m ON m.project_id=p.id
           WHERE m.user_id=u.id AND p.kind<>'platform') AS old_member_only,
         (SELECT COUNT(DISTINCT x.pid) FROM (
             SELECT m2.project_id AS pid FROM project_member m2 WHERE m2.user_id=u.id
             UNION SELECT p2.id FROM project p2 WHERE p2.owner_id=u.id
             UNION SELECT r.project_id FROM project_group_role r
                     JOIN user_group_member gm ON gm.group_id=r.group_id
                    WHERE gm.user_id=u.id) x
           JOIN project pp ON pp.id=x.pid AND pp.kind<>'platform') AS new_three_source
    FROM app_user u
   WHERE u.status=1
   ORDER BY u.id LIMIT 15;" 2>&1
  echo '```'
  echo
  echo "## 2. 三源各自命中数（同一项目多源只算一次）"
  echo
  echo '```'
  mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" "$DB_NAME" -t -e "
  SELECT SUM(src='OWNER') AS owner_rows, SUM(src='MEMBER') AS member_rows,
         SUM(src='GROUP') AS group_rows, COUNT(*) AS distinct_pairs
    FROM (
      SELECT p.owner_id AS uid, p.id AS pid, 'OWNER' AS src FROM project p WHERE p.owner_id IS NOT NULL
      UNION ALL
      SELECT m.user_id, m.project_id, 'MEMBER' FROM project_member m
      UNION ALL
      SELECT gm.user_id, r.project_id, 'GROUP' FROM project_group_role r
              JOIN user_group_member gm ON gm.group_id=r.group_id
    ) s JOIN project pj ON pj.id=s.pid AND pj.kind<>'platform';" 2>&1
  echo '```'
  echo
  echo "## 3. 组授权事实（判权据此生效）"
  echo
  echo '```'
  mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" "$DB_NAME" -t -e "
  SELECT r.project_id, r.group_id, g.name AS group_name, r.role_code,
         (SELECT COUNT(*) FROM user_group_member m WHERE m.group_id=r.group_id) AS members
    FROM project_group_role r JOIN user_group g ON g.id=r.group_id ORDER BY r.project_id;" 2>&1
  echo '```'
  echo
  echo "## 4. 孤儿复查（V11_66 建外键后必须为 0）"
  echo
  echo '```'
  mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" "$DB_NAME" -t -e "
  SELECT 'user_group_member' AS tbl, COUNT(*) AS orphans FROM user_group_member m
    LEFT JOIN app_user u ON u.id=m.user_id LEFT JOIN user_group g ON g.id=m.group_id
   WHERE u.id IS NULL OR g.id IS NULL
  UNION ALL
  SELECT 'project_group_role', COUNT(*) FROM project_group_role r
    LEFT JOIN project p ON p.id=r.project_id LEFT JOIN user_group g ON g.id=r.group_id
   WHERE p.id IS NULL OR g.id IS NULL;" 2>&1
  echo '```'
} | tee docs/evidence/v5.9.0/sql/visibility-union.md
