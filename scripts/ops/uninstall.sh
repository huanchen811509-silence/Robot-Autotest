#!/usr/bin/env bash
# 交付包拆卸（FR-v3.11-D04）：停服务 → 备份运行数据 → 移除程序文件。
#
#   ./uninstall.sh --prefix /opt/robot-autotest                 # dry-run
#   ./uninstall.sh --prefix /opt/robot-autotest --apply        # 交互确认后执行
#   ./uninstall.sh --prefix /opt/robot-autotest --apply --yes  # 非交互（自动化用）
#
# 硬护栏（对应运维红线）：
#   - 默认 dry-run；--apply 前列出**每一条**待删路径；
#   - 目标必须含本产品的 MANIFEST.json，否则拒绝（防止把任意目录删掉）；
#   - 先停服务、先备份 env / logs / var / backups，备份失败立即中止；
#   - **从不碰数据库**：不 DROP、不 TRUNCATE，数据保留由 DBA 决定；
#   - 只删明确列出的程序子项，逐项删除、每批校验，不用宽通配符。
set -euo pipefail
# shellcheck source=/dev/null
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

PREFIX=""
APPLY=0
YES=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) need_arg "$@"; PREFIX="$2"; shift 2 ;;
    --dry-run) APPLY=0; shift ;;
    --apply) APPLY=1; shift ;;
    --yes) YES=1; shift ;;
    --help|-h) printf '用法: uninstall.sh --prefix DIR [--apply] [--yes]\n'; usage_tail; exit 0 ;;
    *) die "未知参数 $1" ;;
  esac
done

[[ -n "$PREFIX" ]] || die '必须显式给 --prefix（拒绝就地推断）'
case "$PREFIX" in
  /|/home|/root|/usr|/etc|/var|"$HOME"|"$ROOT") die "拒绝拆卸系统目录 / 家目录 / 当前交付包：$PREFIX" ;;
esac
[[ -d "$PREFIX" ]] || die "目录不存在：$PREFIX"
[[ -f "$PREFIX/MANIFEST.json" ]] || die "目标目录没有 MANIFEST.json，不认定为本产品安装：$PREFIX"

KEEP_DIRS=(logs var backups)
PROGRAM_ITEMS=(MANIFEST.json VERSION CHANGELOG.md README.md install.sh uninstall.sh bin app entry db deploy docs)
BACKUP_ROOT="${ROBOT_UNINSTALL_BACKUP:-$HOME/robot-autotest-uninstall-backups}"
STAMP="$(date '+%Y%m%d-%H%M%S')"
BACKUP_DIR="$BACKUP_ROOT/$STAMP"

printf '\n[ops] ⚠️ 此操作非常危险，可能导致不可逆的数据丢失！\n'
printf '[ops] 目标目录：%s\n' "$PREFIX"
printf '[ops] 将移除以下程序项（逐项删除）：\n'
for item in "${PROGRAM_ITEMS[@]}"; do
  [[ -e "$PREFIX/$item" ]] && printf '[ops]   - %s/%s\n' "$PREFIX" "$item"
done
printf '[ops] 将保留并备份：%s\n' "${KEEP_DIRS[*]}"
printf '[ops] 数据库：不动（不 DROP / 不 TRUNCATE / 不改任何表）\n'
printf '[ops] 备份位置：%s\n' "$BACKUP_DIR"
printf '[ops] 具体风险：程序文件移除后服务立即不可用；日志与运行数据只在备份里保留一份。\n\n'

if [[ "$APPLY" == "0" ]]; then
  log 'dry-run：未停服务、未删除、未备份。确认后加 --apply。'
  exit 0
fi

if [[ "$YES" == "0" ]]; then
  [[ -t 0 ]] || die '非交互环境必须显式给 --yes 才能拆卸'
  printf '[ops] 请输入 UNINSTALL 确认：'
  read -r answer
  [[ "$answer" == "UNINSTALL" ]] || die '已取消'
fi

# 1) 停服务：只停**这一套安装**的进程。
#    systemd --user unit 是用户级全局资源，开发机上的 unit 指向 ~/robot-autotest；
#    若无条件 stop，会把别处正在跑的服务停掉。只有 install.sh --systemd 写下的
#    var/systemd-managed 标记能证明这批 unit 属于本安装根。
if [[ -f "$PREFIX/var/systemd-managed" ]] && has_cmd systemctl; then
  log '停止本安装根托管的 systemd unit'
  systemctl --user stop robot-autotest-entry.service 2>/dev/null || true
  systemctl --user stop robot-autotest-backend.service 2>/dev/null || true
fi
for pidfile in "$PREFIX/var/entry.pid" "$PREFIX/var/backend.pid"; do
  [[ -f "$pidfile" ]] || continue
  pid="$(cat "$pidfile" 2>/dev/null || true)"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    log "停止本安装根的 pid=$pid ($(basename "$pidfile"))"
    kill "$pid" 2>/dev/null || true
  fi
done

# 2) 备份，失败即中止（不带着未备份的数据往下走）。
mkdir -p "$BACKUP_DIR"
if [[ -f "$ENV_FILE" ]]; then
  cp -a "$ENV_FILE" "$BACKUP_DIR/env"
  chmod 600 "$BACKUP_DIR/env"
fi
for d in "${KEEP_DIRS[@]}"; do
  if [[ -d "$PREFIX/$d" ]]; then
    cp -a "$PREFIX/$d" "$BACKUP_DIR/$d"
  fi
done
[[ -f "$BACKUP_DIR/env" ]] || warn '未找到凭据文件，备份中不含 env'
log "备份完成：$BACKUP_DIR（$(du -sh "$BACKUP_DIR" 2>/dev/null | cut -f1)）"

# 3) 逐项删除，每批不超过 10 项，删完核对。
batch=0
for item in "${PROGRAM_ITEMS[@]}"; do
  target="$PREFIX/$item"
  [[ -e "$target" ]] || continue
  rm -rf -- "$target"
  [[ ! -e "$target" ]] || die "删除失败：$target（已中止，备份在 $BACKUP_DIR）"
  batch=$((batch + 1))
  if (( batch % 10 == 0 )); then
    log "已删除 $batch 项，继续"
  fi
done

# 目录本身留着（放备份说明），只写一份还原指引。
cat > "$PREFIX/REMOVED.txt" <<EOF
本目录的 AIDC AutoTest 程序文件已于 $STAMP 拆卸。
运行数据备份：$BACKUP_DIR
数据库未改动；如需恢复，重新解压交付包并按 docs/DELIVERY-GUIDE.md 安装，
再把备份中的 logs / var / backups 复制回安装目录。
EOF

ok "拆卸完成：程序项 $batch 个；备份 $BACKUP_DIR；数据库未改动"
printf 'UNINSTALL PASS prefix=%s backup=%s\n' "$PREFIX" "$BACKUP_DIR"
