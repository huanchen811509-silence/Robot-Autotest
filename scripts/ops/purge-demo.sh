#!/usr/bin/env bash
# 演示/开发数据清理（v5.9.0 / FR-v5.9-09 / W59-24）。
#
# 为什么单独一支脚本、而不是「管理员在界面上点删除」：
#   演示数据的判定只有一处权威——`is_demo = 1` 这一位。界面上删，删的是「当下看得见的」；
#   而演示数据散落在用户、项目、以及项目下的用例/报告里，逐页点会漏，且漏了没人发现。
#
# 三条护栏（缺一条都不执行）：
#   1. 默认 **dry-run**：不加 `--apply` 只打印将影响的行数，一行都不删；
#   2. 加 `--apply` 还不够，必须同时设 `PURGE_DEMO_CONFIRM=YES`；
#   3. 只认 `is_demo = 1`。**禁止**按 code / name 前缀或 LIKE 匹配——
#      改个名就漏，改名后的真实项目还会被误删（评估 06 §2.2）。
#
# 用法：
#   scripts/ops/purge-demo.sh                 # 盘点（默认，安全）
#   PURGE_DEMO_CONFIRM=YES scripts/ops/purge-demo.sh --apply
#
# 说明：本脚本只删「演示用户」与「演示项目」的**主行与成员关系**。
# 项目下的用例/执行/报告请通过项目删除接口处理（那里有级联与审计），
# 脚本会把这些从属数据的条数打印出来，供人工决定是否再走接口删除。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../db-common.sh
source "$ROOT/scripts/db-common.sh"

APPLY=0
for arg in "$@"; do
    case "$arg" in
        --apply) APPLY=1 ;;
        -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
        *) echo "[purge-demo] 未知参数：$arg（仅支持 --apply）" >&2; exit 2 ;;
    esac
done

q() { db_exec "$DB_NAME" -e "$1"; }

demo_users=$(q "SELECT COUNT(*) FROM app_user WHERE is_demo = 1")
demo_projects=$(q "SELECT COUNT(*) FROM project WHERE is_demo = 1")
# 从属数据：演示项目下的用例、执行记录、测试报告。只统计，不在本脚本里删。
demo_cases=$(q "SELECT COUNT(*) FROM test_case c JOIN project p ON p.id = c.project_id
                 WHERE p.is_demo = 1" || echo 0)
# 表名以 db/migration 为准：run / test_result / test_report（不是 test_run）。
demo_runs=$(q "SELECT COUNT(*) FROM run r JOIN project p ON p.id = r.project_id
                WHERE p.is_demo = 1")
demo_results=$(q "SELECT COUNT(*) FROM test_result tr JOIN run r ON r.run_id = tr.run_id
                   JOIN project p ON p.id = r.project_id WHERE p.is_demo = 1")
# test_report 的历史结构里没有 project_id（经 run 关联），取不到就显示 n/a 而不是让脚本崩。
demo_reports=$(q "SELECT COUNT(*) FROM test_report t JOIN run r ON r.run_id = t.run_id
                   JOIN project p ON p.id = r.project_id WHERE p.is_demo = 1" || echo "n/a")

echo "[purge-demo] 目标库：${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "[purge-demo] is_demo=1 盘点"
printf '  %-16s %s\n' "app_user" "$demo_users"
printf '  %-16s %s\n' "project" "$demo_projects"
echo "[purge-demo] 演示项目下的从属数据（本脚本不删，需走项目删除接口）"
printf '  %-16s %s\n' "test_case" "$demo_cases"
printf '  %-16s %s\n' "run" "$demo_runs"
printf '  %-16s %s\n' "test_result" "$demo_results"
printf '  %-16s %s\n' "test_report" "$demo_reports"

if [[ "$APPLY" -eq 0 ]]; then
    echo "[purge-demo] dry-run：未删除任何行。"
    echo "[purge-demo] 确认无误后执行：PURGE_DEMO_CONFIRM=YES $0 --apply"
    exit 0
fi

if [[ "${PURGE_DEMO_CONFIRM:-}" != "YES" ]]; then
    echo "[purge-demo] 拒绝执行：--apply 需要同时设置 PURGE_DEMO_CONFIRM=YES" >&2
    echo "[purge-demo] 这是为了防止「手滑多打一个 --apply」把演示数据连同真实数据一起删掉。" >&2
    exit 3
fi

echo "[purge-demo] 开始删除（仅 is_demo=1 的主行与成员关系）"
# 顺序：先关系，后主行。关系表没有 is_demo，只能按主行 id 关联删除。
q "DELETE FROM project_member WHERE project_id IN (SELECT id FROM project WHERE is_demo = 1)"
q "DELETE FROM project_member_role WHERE project_id IN (SELECT id FROM project WHERE is_demo = 1)"
q "DELETE FROM project_group_role WHERE project_id IN (SELECT id FROM project WHERE is_demo = 1)"
q "DELETE FROM user_group_member WHERE user_id IN (SELECT id FROM app_user WHERE is_demo = 1)"
q "DELETE FROM project WHERE is_demo = 1"
q "DELETE FROM app_user WHERE is_demo = 1"

echo "[purge-demo] 完成，复核："
printf '  %-16s %s\n' "app_user" "$(q "SELECT COUNT(*) FROM app_user WHERE is_demo = 1")"
printf '  %-16s %s\n' "project" "$(q "SELECT COUNT(*) FROM project WHERE is_demo = 1")"
