#!/usr/bin/env bash
# 交付包安装（FR-v3.11-D01 / TC-D02）：把 tar.gz 解出的内容装配到目标目录。
#
#   ./install.sh --prefix /opt/robot-autotest              # dry-run，只打印计划
#   ./install.sh --prefix /opt/robot-autotest --apply      # 真装
#   ./install.sh --prefix /opt/robot-autotest --apply --systemd   # 顺带装 user unit（不自动启用）
#
# 硬护栏：
#   1. 默认 dry-run，必须显式 --apply 才写盘；
#   2. 只从**带 MANIFEST.json 的交付包**安装，源码树不装（避免把开发态当生产）；
#   3. 目标目录已存在且非空时拒绝覆盖，除非显式 --upgrade（会先备份配置）；
#   4. 不自动建库、不自动迁移、不自动起服务、不写入任何口令。
set -euo pipefail
# shellcheck source=/dev/null
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

PREFIX=""
APPLY=0
UPGRADE=0
WITH_SYSTEMD=0
YES=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) need_arg "$@"; PREFIX="$2"; shift 2 ;;
    --dry-run) APPLY=0; shift ;;
    --apply) APPLY=1; shift ;;
    --upgrade) UPGRADE=1; shift ;;
    --systemd) WITH_SYSTEMD=1; shift ;;
    --yes) YES=1; shift ;;
    --help|-h)
      printf '用法: install.sh --prefix DIR [--apply] [--upgrade] [--systemd] [--yes]\n'
      usage_tail; exit 0 ;;
    *) die "未知参数 $1" ;;
  esac
done

[[ -n "$PREFIX" ]] || die '必须给 --prefix（不允许就地安装到当前目录）'
case "$PREFIX" in
  /|/home|/root|/usr|/etc|/var|"$HOME") die "拒绝把安装根设为系统目录或家目录：$PREFIX" ;;
esac

[[ "$LAYOUT" == "bundle" ]] || die '只能在交付包里运行；源码树请先 bash scripts/package-release.sh 打包'
[[ -f "$ROOT/MANIFEST.json" ]] || die '缺少 MANIFEST.json，交付包可能不完整'
[[ -f "$DEPLOY_DIR/env.example" ]] || die '缺少 deploy/env.example，交付包可能不完整'

log "交付包    $ROOT"
log "版本      $(version_of)"
log "目标目录  $PREFIX"
log "布局      $LAYOUT / --apply=$APPLY / --upgrade=$UPGRADE / --systemd=$WITH_SYSTEMD"

# 安装前体检：CI/离线场景只查工具链与产物，不要求服务已起。
"$OPS_DIR/doctor.sh" --ci >/dev/null || die '交付包体检未通过，先跑 bin/doctor.sh --ci 看具体项'

if [[ -e "$PREFIX" ]]; then
  if [[ ! -d "$PREFIX" ]]; then
    die "目标路径已存在且不是目录：$PREFIX"
  fi
  if [[ -n "$(ls -A "$PREFIX" 2>/dev/null)" ]]; then
    if [[ -f "$PREFIX/MANIFEST.json" && "$UPGRADE" == "1" ]]; then
      log '检测到既有安装，--upgrade 已给：将保留 env / logs / var / backups'
    else
      die "目标目录非空：$PREFIX（拒绝覆盖；如确认是升级请加 --upgrade）"
    fi
  fi
fi

ENV_TARGET="$ENV_FILE"
UNIT_TARGET_DIR="$HOME/.config/systemd/user"

plan() {
  printf '[ops] 将执行：\n'
  printf '[ops]   1) 创建目录 %s 及 logs/ var/ backups/\n' "$PREFIX"
  printf '[ops]   2) 复制交付包内容（app/ entry/ db/ deploy/ docs/ bin/ MANIFEST.json …）\n'
  printf '[ops]   3) 凭据模板：若 %s 不存在，从 deploy/env.example 复制（口令留空，需人工填）\n' "$ENV_TARGET"
  if [[ "$WITH_SYSTEMD" == "1" ]]; then
    printf '[ops]   4) 生成 systemd --user unit 到 %s（只生成 + daemon-reload，不 enable）\n' "$UNIT_TARGET_DIR"
  else
    printf '[ops]   4) 跳过 systemd（unit 样例留在 %s/deploy/，可稍后手工安装）\n' "$PREFIX"
  fi
  printf '[ops] 不执行：建库 / 迁移 / 启动服务 / 写口令。后续命令见 docs/DELIVERY-GUIDE.md\n'
}
plan

if [[ "$APPLY" == "0" ]]; then
  log 'dry-run：未写任何文件。确认后加 --apply。'
  exit 0
fi

if [[ "$YES" == "0" ]] && [[ -t 0 ]]; then
  printf '[ops] 确认安装到 %s ？输入 yes 继续：' "$PREFIX"
  read -r answer
  [[ "$answer" == "yes" ]] || die '已取消'
fi

mkdir -p "$PREFIX" "$PREFIX/logs" "$PREFIX/var" "$PREFIX/backups" "$(dirname "$ENV_TARGET")"

# 逐项复制，保留既有运行数据；目录用 `src/.` 语义，避免升级时复制成 bin/bin。
for item in MANIFEST.json VERSION CHANGELOG.md README.md install.sh uninstall.sh \
            bin app entry db deploy docs; do
  [[ -e "$ROOT/$item" ]] || die "交付包缺项：$item"
  if [[ -d "$ROOT/$item" ]]; then
    mkdir -p "$PREFIX/$item"
    cp -a "$ROOT/$item/." "$PREFIX/$item/"
  else
    cp -a "$ROOT/$item" "$PREFIX/$item"
  fi
done

if [[ ! -f "$ENV_TARGET" ]]; then
  cp "$DEPLOY_DIR/env.example" "$ENV_TARGET"
  chmod 600 "$ENV_TARGET"
  warn "已生成凭据模板 $ENV_TARGET；口令为空，启动前必须填写"
else
  log "沿用既有凭据文件 $ENV_TARGET（内容未读取、未打印）"
fi

if [[ "$WITH_SYSTEMD" == "1" ]] && has_cmd systemctl; then
  mkdir -p "$UNIT_TARGET_DIR"
  for unit in robot-autotest-backend.service robot-autotest-entry.service; do
    [[ -f "$DEPLOY_DIR/$unit" ]] || die "缺少 unit 样例 $unit"
    # 样例里的 %h/robot-autotest 是开发机约定；交付包渲染成实际 prefix。
    sed -e "s|%h/robot-autotest|$PREFIX|g" \
        -e "s|WorkingDirectory=.*|WorkingDirectory=$PREFIX|" \
        "$DEPLOY_DIR/$unit" > "$UNIT_TARGET_DIR/$unit"
  done
  systemctl --user daemon-reload 2>/dev/null || warn 'daemon-reload 未成功（无 systemd 用户实例时属正常）'
  # 托管标记：ctl.sh / uninstall.sh 只有看到它才允许操作用户级 systemd unit，
  # 避免临时目录或另一套安装误停开发机上正在跑的服务。
  printf 'managed\n' > "$PREFIX/var/systemd-managed"
  log 'unit 已生成但未 enable；启用命令见 docs/DELIVERY-GUIDE.md'
fi

ok "安装完成：$PREFIX"
printf '[ops] 下一步：编辑 %s → bin/db-migrate.sh --apply（仅空库）→ bin/ctl.sh start\n' "$ENV_TARGET"
printf 'INSTALL PASS prefix=%s version=%s\n' "$PREFIX" "$(version_of)"
