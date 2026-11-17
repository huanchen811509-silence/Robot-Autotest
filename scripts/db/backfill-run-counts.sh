#!/usr/bin/env bash
# v5.22.0 / FR-v5.22-05 判据 05-2 / 05-3：按 test_result 聚合**幂等**回填 run 的计数列。
#
# 为什么要有这个脚本：
# `run` 表有两个时期的计数列，各自只在半条链路上被写——
#   * total_tests / passed_tests / failed_tests  （V4_1，pytest 执行期写，passed 恒 0）
#   * pass_count  / fail_count  / skip_count     （V5_1，报告入库写，只有走过 ingest 的 run 才有）
# 于是同一个 run 在执行页与报告页显示两套数字（L-19-06：明细 5/4/61，run 表 70/0/4）。
# 迁移 V11_83 补上了缺失的 skipped_tests，本脚本把**历史行**也归到同一口径。
#
# 口径（与 FR-05、ReportIngestService 落库时的重算完全一致，同一段 SQL）：
#   total    = passed + failed + skipped
#   passed   = COUNT(verdict='PASS')
#   failed   = COUNT(verdict='FAIL')
#   skipped  = COUNT(verdict='SKIP')
#   verdict='DISPATCHED'（已下发未落定）不计入任何一列，单列可查（dispatched=）
#
# 幂等性怎么证明：
#   摘要行（`[backfill] SUMMARY ...`）只报**回填后的状态**，不含「本次改了多少行」。
#   首次运行前状态差异 >0，运行后 mismatch=0；再次运行 mismatch 仍为 0，且 runs/withDetail/
#   total/passed/failed/skipped/backfilled/pending 全部一致 —— 比对命令只取 SUMMARY 行：
#     bash scripts/db/backfill-run-counts.sh | grep '^\[backfill\] SUMMARY' > a
#     bash scripts/db/backfill-run-counts.sh | grep '^\[backfill\] SUMMARY' > b
#     diff a b && echo 幂等
#   「本次修掉多少差异」单独打成 `repaired mismatchBefore=.. mismatchAfter=..` 行，
#   不参与摘要比对（首次必然 >0，第二次为 0，这正是幂等该有的样子）。
#
# 没有明细的 run（本库 34 个）**保持 skipped_tests=NULL**，不伪造 0：
# 「没跑过」与「跑了 0 条」必须分得开，这也是 pending= 这个读数的意义。
#
# 用法：
#   bash scripts/db/backfill-run-counts.sh             # 真回填
#   bash scripts/db/backfill-run-counts.sh --dry-run   # 只报差异，不写库
set -uo pipefail

DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "[backfill] 未知参数: $arg" >&2; exit 2 ;;
    esac
done

source "$(dirname "${BASH_SOURCE[0]}")/../db-common.sh"

# 聚合子查询只写一遍：回填 UPDATE、差异检查、摘要读数三处共用，避免三份 SQL 各说各话。
AGGREGATE="SELECT \`run_id\` AS \`rid\`,
                  SUM(\`verdict\` = 'PASS') AS \`p\`,
                  SUM(\`verdict\` = 'FAIL') AS \`f\`,
                  SUM(\`verdict\` = 'SKIP') AS \`s\`
             FROM \`test_result\`
            GROUP BY \`run_id\`"

# 差异检查用 null-safe 比较（<=>）：skipped_tests 为 NULL 时 `<>` 结果是 NULL，
# 会被 WHERE 当成 false 悄悄漏掉——这正是「以为回填了其实没回填」的经典假绿。
MISMATCH="SELECT COUNT(*) FROM \`run\` r
            JOIN (${AGGREGATE}) agg ON agg.\`rid\` = r.\`id\`
           WHERE NOT (r.\`total_tests\`   <=> agg.\`p\` + agg.\`f\` + agg.\`s\`)
              OR NOT (r.\`passed_tests\`  <=> agg.\`p\`)
              OR NOT (r.\`failed_tests\`  <=> agg.\`f\`)
              OR NOT (r.\`skipped_tests\` <=> agg.\`s\`)
              OR NOT (r.\`pass_count\`    <=> agg.\`p\`)
              OR NOT (r.\`fail_count\`    <=> agg.\`f\`)
              OR NOT (r.\`skip_count\`    <=> agg.\`s\`)"

read_one() {
    # 单值读；空结果按 0 处理。不用 `x=$(cmd | wc -l)`——无匹配时管道返回 1，
    # 在 pipefail 下会把整个脚本带走（v5.18 踩过）。
    local value
    value="$(db_exec -D "$DB_NAME" -e "$1" 2>/dev/null)" || value=""
    value="${value//[$'\r\n\t ']/}"
    printf '%s' "${value:-0}"
}

summary_body() {
    local runs with_detail backfilled pending dispatched
    local sum_total sum_pass sum_fail sum_skip mismatch
    runs="$(read_one "SELECT COUNT(*) FROM \`run\`")"
    with_detail="$(read_one "SELECT COUNT(DISTINCT \`run_id\`) FROM \`test_result\`")"
    backfilled="$(read_one "SELECT COUNT(*) FROM \`run\` WHERE \`skipped_tests\` IS NOT NULL")"
    pending=$((runs - backfilled))
    dispatched="$(read_one "SELECT COUNT(*) FROM \`test_result\` WHERE \`verdict\` = 'DISPATCHED'")"
    sum_total="$(read_one "SELECT COALESCE(SUM(\`total_tests\`),0) FROM \`run\` WHERE \`skipped_tests\` IS NOT NULL")"
    sum_pass="$(read_one "SELECT COALESCE(SUM(\`passed_tests\`),0) FROM \`run\` WHERE \`skipped_tests\` IS NOT NULL")"
    sum_fail="$(read_one "SELECT COALESCE(SUM(\`failed_tests\`),0) FROM \`run\` WHERE \`skipped_tests\` IS NOT NULL")"
    sum_skip="$(read_one "SELECT COALESCE(SUM(\`skipped_tests\`),0) FROM \`run\` WHERE \`skipped_tests\` IS NOT NULL")"
    mismatch="$(read_one "$MISMATCH")"
    echo "runs=$runs withDetail=$with_detail backfilled=$backfilled pending=$pending" \
         "dispatched=$dispatched total=$sum_total passed=$sum_pass failed=$sum_fail" \
         "skipped=$sum_skip mismatch=$mismatch"
}

echo "[backfill] $DB_USERNAME@$DB_HOST:$DB_PORT/$DB_NAME  模式=$([[ $DRY_RUN -eq 1 ]] && echo dry-run || echo apply)"

# 迁移必须已生效：列不在就报错退出，而不是让 UPDATE 抛一个看不懂的 1054。
COLUMN_EXISTS="$(read_one "SELECT COUNT(*) FROM information_schema.columns
                             WHERE table_schema='$DB_NAME' AND table_name='run'
                               AND column_name='skipped_tests'")"
if [[ "$COLUMN_EXISTS" != "1" ]]; then
    echo "[backfill] 缺少 run.skipped_tests，请先跑 bash scripts/db-migrate.sh" >&2
    exit 1
fi

BEFORE_SUMMARY="$(summary_body)"
echo "[backfill] BEFORE $BEFORE_SUMMARY"

if [[ $DRY_RUN -eq 1 ]]; then
    echo "[backfill] dry-run：未写库。待回填差异行数 = ${BEFORE_SUMMARY##*mismatch=}"
    exit 0
fi

# 一条 UPDATE 落定 7 列：分两条写就有中间态，第二条失败时又多出一个两套数字的 run。
# `WHERE r.project_id > 0` 只排除脏数据（project_id 为 0/NULL 的历史垃圾行），不是租户过滤——
# 本脚本是全库运维脚本，走 db_exec 直连，不经过 TenantGuard。
db_exec -D "$DB_NAME" -e "
UPDATE \`run\` r
  JOIN (${AGGREGATE}) agg ON agg.\`rid\` = r.\`id\`
   SET r.\`total_tests\`   = agg.\`p\` + agg.\`f\` + agg.\`s\`,
       r.\`passed_tests\`  = agg.\`p\`,
       r.\`failed_tests\`  = agg.\`f\`,
       r.\`skipped_tests\` = agg.\`s\`,
       r.\`pass_count\`    = agg.\`p\`,
       r.\`fail_count\`    = agg.\`f\`,
       r.\`skip_count\`    = agg.\`s\`,
       r.\`updated_at\`    = CURRENT_TIMESTAMP(3)
 WHERE r.\`project_id\` > 0;" >/dev/null 2>&1

# 不报「改了多少行」：mysql CLI 默认带 CLIENT_FOUND_ROWS，UPDATE 回报的是**匹配行数**，
# 幂等的第二次跑照样报 516，看起来像没生效。真正有意义的是差异行数从多少修到了多少。
echo "[backfill] repaired mismatchBefore=${BEFORE_SUMMARY##*mismatch=} mismatchAfter=$(read_one "$MISMATCH")"

echo "[backfill] SUMMARY $(summary_body)"

# 收口判据：差异行数必须为 0，否则脚本要以非 0 退出，不能靠人眼看摘要。
FINAL_MISMATCH="$(read_one "$MISMATCH")"
if [[ "$FINAL_MISMATCH" != "0" ]]; then
    echo "[backfill] FAIL：回填后仍有 $FINAL_MISMATCH 行与明细聚合不一致" >&2
    exit 1
fi
echo "[backfill] OK"
