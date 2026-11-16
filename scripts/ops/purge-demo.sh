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
#   scripts/ops/purge-demo.sh --json          # 机器可读清单（逐条对象，供复核与 diff）
#   PURGE_DEMO_CONFIRM=YES scripts/ops/purge-demo.sh --apply
#
# 说明：本脚本只删「演示用户」与「演示项目」的**主行与成员关系**。
# 项目下的用例集/用例/执行/报告请通过项目删除接口处理（那里有级联与审计），
# 脚本会把这些从属数据的条数打印出来，供人工决定是否再走接口删除。
#
# v5.17.0 / W67-01 补充：原先只统计 test_case/run/test_result/test_report，
# **漏了 test_set**，导致「2 集 + 10 例 + 1 报告」的 13 个对象里有 2 个不被覆盖，
# 清单也无法逐条复核。现补齐 test_set 计数并新增 --json 逐条清单。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../db-common.sh
source "$ROOT/scripts/db-common.sh"

APPLY=0
JSON=0
for arg in "$@"; do
    case "$arg" in
        --apply) APPLY=1 ;;
        --json) JSON=1 ;;
        -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
        *) echo "[purge-demo] 未知参数：$arg（仅支持 --apply / --json）" >&2; exit 2 ;;
    esac
done

q() { db_exec "$DB_NAME" -e "$1"; }

demo_users=$(q "SELECT COUNT(*) FROM app_user WHERE is_demo = 1")
demo_projects=$(q "SELECT COUNT(*) FROM project WHERE is_demo = 1")
# 从属数据：演示项目下的用例集、用例、执行记录、测试报告。只统计，不在本脚本里删。
demo_sets=$(q "SELECT COUNT(*) FROM test_set s JOIN project p ON p.id = s.project_id
                WHERE p.is_demo = 1" || echo 0)
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

if [[ "$JSON" -eq 0 ]]; then
echo "[purge-demo] 目标库：${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "[purge-demo] is_demo=1 盘点"
printf '  %-16s %s\n' "app_user" "$demo_users"
printf '  %-16s %s\n' "project" "$demo_projects"
echo "[purge-demo] 演示项目下的从属数据（本脚本不删，需走项目删除接口）"
printf '  %-16s %s\n' "test_set" "$demo_sets"
printf '  %-16s %s\n' "test_case" "$demo_cases"
printf '  %-16s %s\n' "run" "$demo_runs"
printf '  %-16s %s\n' "test_result" "$demo_results"
printf '  %-16s %s\n' "test_report" "$demo_reports"
fi

# 机器可读清单：逐条对象，供复核与两次运行的 diff。
# 只读，任何情况下都不删（即使同时给了 --apply 也先输出清单再退出）。
jrows() {
    local out
    out=$(q "$1" 2>/dev/null || true)
    if [[ -z "$out" || "$out" == "NULL" ]]; then printf '[]'; else printf '%s' "$out"; fi
}
if [[ "$JSON" -eq 1 ]]; then
    printf '{\n'
    printf '  "db": "%s:%s/%s",\n' "$DB_HOST" "$DB_PORT" "$DB_NAME"
    printf '  "totals": {"app_user": %s, "project": %s, "test_set": %s, "test_case": %s, "run": %s, "test_result": %s, "test_report": %s},\n' \
        "$demo_users" "$demo_projects" "$demo_sets" "$demo_cases" "$demo_runs" "$demo_results" \
        "$([[ "$demo_reports" == "n/a" ]] && echo null || echo "$demo_reports")"
    printf '  "objects": {\n'
    printf '    "app_user": %s,\n' \
        "$(jrows "SELECT JSON_ARRAYAGG(JSON_OBJECT('id', id, 'username', username)) FROM app_user WHERE is_demo = 1")"
    printf '    "project": %s,\n' \
        "$(jrows "SELECT JSON_ARRAYAGG(JSON_OBJECT('id', id, 'code', code, 'name', name)) FROM project WHERE is_demo = 1")"
    printf '    "test_set": %s,\n' \
        "$(jrows "SELECT JSON_ARRAYAGG(JSON_OBJECT('id', s.id, 'setCode', s.set_code, 'name', s.name, 'kind', s.kind)) FROM test_set s JOIN project p ON p.id = s.project_id WHERE p.is_demo = 1")"
    printf '    "test_case": %s,\n' \
        "$(jrows "SELECT JSON_ARRAYAGG(JSON_OBJECT('id', c.id, 'caseCode', c.case_code, 'title', c.title)) FROM test_case c JOIN project p ON p.id = c.project_id WHERE p.is_demo = 1")"
    printf '    "run": %s,\n' \
        "$(jrows "SELECT JSON_ARRAYAGG(JSON_OBJECT('runId', r.run_id, 'status', r.run_status)) FROM run r JOIN project p ON p.id = r.project_id WHERE p.is_demo = 1")"
    printf '    "test_result": %s,\n' \
        "$(jrows "SELECT JSON_ARRAYAGG(JSON_OBJECT('id', tr.id, 'runId', tr.run_id)) FROM test_result tr JOIN run r ON r.run_id = tr.run_id JOIN project p ON p.id = r.project_id WHERE p.is_demo = 1")"
    printf '    "test_report": %s\n' \
        "$(jrows "SELECT JSON_ARRAYAGG(JSON_OBJECT('id', t.id, 'runId', t.run_id, 'title', t.title, 'state', t.state)) FROM test_report t JOIN run r ON r.run_id = t.run_id JOIN project p ON p.id = r.project_id WHERE p.is_demo = 1")"
    printf '  }\n'
    printf '}\n'
    [[ "$APPLY" -eq 1 ]] && echo "[purge-demo] --json 为只读清单，已忽略 --apply（未删除任何行）。" >&2
    exit 0
fi

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

# 前置门禁（v5.17.0 / W67-01）：
# 库里有 26 张表以 RESTRICT 引用 project(id)，只有 project_asset / project_group_role 是 CASCADE。
# 演示项目只要还有一行从属数据（1 台设备、1 条用例都算），
# `DELETE FROM project` 就会 1451 失败；而此前的成员关系已经删掉 —— 留下**半删状态**，
# 重跑也回不到原样。所以先数一遍：有从属数据就拒绝，并指明走项目删除接口。
DEP_TABLES="case_duration_baseline case_failure_disposition case_script_binding ci_trigger credential
device env_profile execution_job execution_plan export_record link manual_execution plan_group
project_label project_setting robot_file run schedule test_case test_scenario test_set topology_node"

dep_rows=0
dep_detail=""
for t in $DEP_TABLES; do
    n=$(q "SELECT COUNT(*) FROM $t WHERE project_id IN (SELECT id FROM project WHERE is_demo = 1)" 2>/dev/null || echo 0)
    if [[ "${n:-0}" -gt 0 ]]; then
        dep_rows=$((dep_rows + n))
        dep_detail="${dep_detail}        $(printf '%-24s %s' "$t" "$n")"$'\n'
    fi
done
# run 的子表按 run 关联，不计在 project 外键里，但删 run 时同样会拦。
for spec in test_result:tr test_report:t; do
    tbl=${spec%%:*}; alias=${spec##*:}
    n=$(q "SELECT COUNT(*) FROM $tbl $alias JOIN run r ON r.run_id = $alias.run_id
           JOIN project p ON p.id = r.project_id WHERE p.is_demo = 1" 2>/dev/null || echo 0)
    if [[ "${n:-0}" -gt 0 ]]; then
        dep_rows=$((dep_rows + n))
        dep_detail="${dep_detail}        $(printf '%-24s %s' "$tbl" "$n")"$'\n'
    fi
done

if [[ "$dep_rows" -gt 0 ]]; then
    echo "[purge-demo] 拒绝执行：演示项目下仍有 ${dep_rows} 行从属数据。" >&2
    printf '%s' "$dep_detail" >&2
    echo "[purge-demo] 这些表以 RESTRICT 引用 project(id)，直删会 1451 失败并留下半删状态。" >&2
    echo "[purge-demo] 正确做法：先把演示项目下的用例/报告/执行清空，再回来跑本脚本。" >&2
    echo "[purge-demo] 注意：项目删除接口（DELETE /api/projects/{id}）在有内容时会 409 拒绝（只允许归档），" >&2
    echo "[purge-demo]       所以「调接口」不能替代这一步清理——见 v5.17.0 W67-01 取证记录。" >&2
    exit 4
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
