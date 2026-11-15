#!/usr/bin/env bash
# v5.9.0 W59-06 夹具：造出「旧口径看不见、新口径看得见」的两个真实场景
#   ① 项目 TEST-V59-OWNER：owner 不在 project_member 里（旧口径 0 个可见）
#   ② 项目 TEST-V59-GROUP：用户只通过用户组拿到 viewer（旧口径 0 个可见）
# 两个项目都打 is_demo=1，可被 scripts/ops/purge-demo.sh 清理。
set -u
set -a
. "$HOME/.config/robot-autotest/env"
set +a
export MYSQL_PWD="$DB_PASSWORD"
Q() { mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" "$DB_NAME" -N -B -e "$1"; }

# 取两个真实账号：owner 用 qa-e2e-pm，组员用 qa-e2e-engineer
PM_ID=$(Q "SELECT id FROM app_user WHERE username='qa-e2e-pm' LIMIT 1;")
EN_ID=$(Q "SELECT id FROM app_user WHERE username='qa-e2e-engineer' LIMIT 1;")
echo "pm=$PM_ID engineer=$EN_ID"

# ① owner 不在成员表
Q "INSERT INTO project (code, name, description, status, owner_id, visibility, test_kernel, kind, is_demo, created_by)
   VALUES ('TEST-V59-OWNER','v5.9 负责人可见性','owner 无成员行，用于验证三源并集',1,$PM_ID,'private','robot','standard',1,$PM_ID)
   ON DUPLICATE KEY UPDATE is_demo=1;"
Q "DELETE FROM project_member WHERE project_id=(SELECT id FROM project WHERE code='TEST-V59-OWNER' LIMIT 1);"

# ② 仅组授权
Q "INSERT INTO user_group (name, description) VALUES ('v59-qa-group','v5.9 判权夹具组')
   ON DUPLICATE KEY UPDATE description=VALUES(description);"
GID=$(Q "SELECT id FROM user_group WHERE name='v59-qa-group' LIMIT 1;")
Q "INSERT IGNORE INTO user_group_member (group_id, user_id) VALUES ($GID, $EN_ID);"
Q "INSERT INTO project (code, name, description, status, owner_id, visibility, test_kernel, kind, is_demo, created_by)
   VALUES ('TEST-V59-GROUP','v5.9 组授权可见性','仅通过用户组授权，无成员行',1,$PM_ID,'private','robot','standard',1,$PM_ID)
   ON DUPLICATE KEY UPDATE is_demo=1;"
PID=$(Q "SELECT id FROM project WHERE code='TEST-V59-GROUP' LIMIT 1;")
Q "INSERT INTO project_group_role (project_id, group_id, role_code) VALUES ($PID, $GID, 'viewer')
   ON DUPLICATE KEY UPDATE role_code='viewer';"
Q "DELETE FROM project_member WHERE project_id=$PID AND user_id=$EN_ID;"

echo "=== 夹具后：两账号旧口径 vs 新口径 ==="
Q "SELECT u.username,
     (SELECT COUNT(*) FROM project p JOIN project_member m ON m.project_id=p.id
       WHERE m.user_id=u.id AND p.kind<>'platform') AS old_member_only,
     (SELECT COUNT(DISTINCT x.pid) FROM (
         SELECT m2.project_id AS pid FROM project_member m2 WHERE m2.user_id=u.id
         UNION SELECT p2.id FROM project p2 WHERE p2.owner_id=u.id
         UNION SELECT r.project_id FROM project_group_role r
                 JOIN user_group_member gm ON gm.group_id=r.group_id WHERE gm.user_id=u.id) x
       JOIN project pp ON pp.id=x.pid AND pp.kind<>'platform') AS new_three_source
   FROM app_user u WHERE u.username IN ('qa-e2e-pm','qa-e2e-engineer');"
