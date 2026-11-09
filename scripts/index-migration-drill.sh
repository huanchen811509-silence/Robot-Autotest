#!/usr/bin/env bash
# 索引型迁移的 down→up 真实演练（只允许集成测试库，库名以 _it 结尾）。
#
# 为什么单独一支：`scripts/migration-down-up-drill.sh` 只认「加列」与「建表」两种 up
# 形态，它比的是 information_schema.columns。V11_27 改的是**索引**（DROP 唯一键 /
# ADD 普通索引），一行列都没动，拿去跑会「通过」一个它根本没看的对象——假绿比不跑更危险。
#
# 判据（任一条不满足即 FAIL）：
#   1. down 前新普通索引存在、旧唯一键不存在
#   2. down 后新索引消失、旧唯一键恢复
#   3. up   后新索引回来、旧唯一键再次消失
#   4. 全程 env_profile 行数不变
#
# 用法：bash scripts/index-migration-drill.sh
# 输出：末行 `[idx-drill] RESULT PASS fails=0`
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

set -a
# shellcheck disable=SC1091
. "$HOME/.config/robot-autotest/env" 2>/dev/null || true
set +a
# shellcheck disable=SC1091
source ./scripts/db-common.sh

# 演练只允许集成库：环境文件里配的是开发库，不报错退出而是改用 robot_it 并明说。
DB_NAME=robot_it
UP=db/migration/V11_27__v3_11_env_snapshot_history.sql
DOWN=db/down/V11_27__v3_11_env_snapshot_history.down.sql
OLD=uk_env_profile_project_name
NEW=idx_env_profile_project_name_time
FAILS=0

# 复合索引在 information_schema.statistics 里是「每列一行」，所以不能数行数：
# 3 列联合索引会回 3，判存在要用 IF(COUNT(*)>0,1,0)（首跑在这里假红过一次）。
idx() {
    db_exec -D "$DB_NAME" -N -B -e "SELECT IF(COUNT(*)>0,1,0)
      FROM information_schema.statistics
      WHERE table_schema='$DB_NAME' AND table_name='env_profile' AND index_name='$1';" 2>/dev/null
}
rows() { db_exec -D "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM env_profile;" 2>/dev/null; }
check() {
    if [[ "$2" == "$3" ]]; then echo "[PASS] $1（$2）";
    else echo "[FAIL] $1：期望 $3 实际 $2"; FAILS=$((FAILS + 1)); fi
}

echo "[idx-drill] db=$DB_NAME migration=V11_27 旧唯一键=$OLD 新普通索引=$NEW"
BEFORE_ROWS="$(rows)"
echo "[idx-drill] 演练前行数=$BEFORE_ROWS"
check "down 前新普通索引存在" "$(idx $NEW)" "1"
check "down 前旧唯一键不存在" "$(idx $OLD)" "0"

echo "===== down ====="
db_exec -D "$DB_NAME" < "$DOWN" || { echo "[idx-drill] down 执行失败" >&2; exit 1; }
check "down 后新普通索引消失" "$(idx $NEW)" "0"
check "down 后旧唯一键恢复" "$(idx $OLD)" "1"

echo "===== up ====="
db_exec -D "$DB_NAME" < "$UP" || { echo "[idx-drill] up 执行失败" >&2; exit 1; }
check "up 后新普通索引回来" "$(idx $NEW)" "1"
check "up 后旧唯一键再次消失" "$(idx $OLD)" "0"
check "全程数据行数不变" "$(rows)" "$BEFORE_ROWS"

if [[ "$FAILS" -gt 0 ]]; then
    echo "[idx-drill] RESULT FAIL fails=$FAILS" >&2
    exit 1
fi
echo "[idx-drill] RESULT PASS fails=0"
