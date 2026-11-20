#!/usr/bin/env bash
# 生成 v5.31.0 基线快照（只读）
set -uo pipefail
set -a; . "$HOME/.config/robot-autotest/env"; set +a
q() { mysql --default-character-set=utf8mb4 -N -B -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" -e "$1" 2>/dev/null; }
OUT="${1:-docs/evidence/v5.31.0/00-baseline.md}"
mkdir -p "$(dirname "$OUT")"
{
  echo "# v5.31.0 基线快照（只读）"
  echo
  echo "> 生成时间：$(date '+%Y-%m-%d %H:%M:%S %Z') ｜ 库：$DB_NAME ｜ 用途：作为 v5.31–v5.34 每步对账基准"
  echo
  echo "| 指标 | 值 |"
  echo "|---|---|"
  echo "| 用例总数 | $(q 'SELECT COUNT(*) FROM test_case') |"
  echo "| 公共库(project 88) | $(q 'SELECT COUNT(*) FROM test_case WHERE project_id=88') |"
  echo "| EHT 库(project 95) | $(q 'SELECT COUNT(*) FROM test_case WHERE project_id=95') |"
  echo "| LX 未定级 | $(q "SELECT COUNT(*) FROM test_case WHERE safety_level='LX'") |"
  echo "| L0 / L1 / L2 | $(q "SELECT CONCAT(SUM(safety_level='L0'),' / ',SUM(safety_level='L1'),' / ',SUM(safety_level='L2')) FROM test_case") |"
  echo "| 非法优先级 | $(q "SELECT COUNT(*) FROM test_case WHERE priority NOT IN ('P0','P1','P2','P3') AND priority IS NOT NULL") |"
  echo "| 待评审(priority=待评审) | $(q "SELECT COUNT(*) FROM test_case WHERE priority='待评审'") |"
  echo "| 评审态 draft/pending/approved | $(q "SELECT CONCAT(SUM(review_status='draft'),' / ',SUM(review_status='pending'),' / ',SUM(review_status='approved')) FROM test_case") |"
  echo "| 用例集总数 | $(q 'SELECT COUNT(*) FROM test_set') |"
  echo "| 样例集 | $(q 'SELECT COUNT(*) FROM test_set WHERE sample_flag=1') |"
  echo "| 脚本绑定 / 已确认 | $(q "SELECT CONCAT(COUNT(*),' / ',SUM(verified=1)) FROM case_script_binding") |"
  echo "| 执行节点 / 在线 | $(q "SELECT CONCAT(COUNT(*),' / ',SUM(status='ONLINE')) FROM execution_node") |"
  echo "| 报告模板 | $(q 'SELECT COUNT(*) FROM report_template') |"
  echo "| 执行计划 | $(q 'SELECT COUNT(*) FROM execution_plan') |"
  echo "| 链路 | $(q 'SELECT COUNT(*) FROM link') |"
  echo
  echo "## 样例集编号现状（前 5）"
  echo
  echo '```'
  q "SELECT set_code, name FROM test_set WHERE sample_flag=1 ORDER BY id LIMIT 5"
  echo '```'
} > "$OUT"
echo "written: $OUT"
