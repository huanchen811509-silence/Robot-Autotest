#!/usr/bin/env bash
# 内置兜底 AI agent（pi coding agent）安装与校验 —— FR-AI-10
#
# 背景：本机可能一个外部 AI CLI 都没有（claude 被安全策略阻断、codex 缺凭据、
# gemini/aider 未装）。pi 是 MIT 许可的最小 agent，`pi -p` 非交互打印模式，
# 随软件包安装后作为 AiCliAdapter.CANDIDATES 的最后一位兜底。
#
# 用法：
#   bash tools/ai/ensure-pi-agent.sh            # 幂等安装/校验（默认）
#   bash tools/ai/ensure-pi-agent.sh --check    # 只校验，不联网、不安装
#   bash tools/ai/ensure-pi-agent.sh --dir DIR  # 自定义安装目录
#
# 退出码：0 已就绪（含首次安装成功）；2 失败。--check 模式同样 0 就绪 / 2 缺失。
set -u

PI_VERSION="1.5.0"
PI_PACKAGE="@earendil-works/pi-coding-agent"
DEST="${HOME}/.local/share/robot-autotest/agents/pi"
MODE="install"

while [ $# -gt 0 ]; do
    case "$1" in
        --check) MODE="check"; shift ;;
        --dir) DEST="${2:?--dir 需要参数}"; shift 2 ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done

NPM_BIN="$DEST/node_modules/.bin/pi"
BIN="$DEST/bin/pi"

log() { printf '[pi-agent] %s\n' "$*"; }

# 生成 wrapper：pi 的 npm bin 用 `#!/usr/bin/env node`，只认调用方 PATH 上的 node。
# 2026-11-08 实测后端把 PATH 收窄后内置 pi 直接 exitCode=127，故由 wrapper 自己找 node。
write_wrapper() {
    mkdir -p "$DEST/bin" || return 1
    cat > "$BIN" <<'WRAPPER'
#!/bin/sh
# 内置 pi 启动器（由 tools/ai/ensure-pi-agent.sh 生成，勿手改）
# 自己解析 node，不依赖调用方 PATH —— 后端 systemd 的 PATH 可能不含 node。
SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
ROOT=$(CDPATH= cd -- "$SELF_DIR/.." && pwd) || exit 1
for d in /opt/node24/bin /usr/local/bin /usr/bin /bin "$HOME/.local/bin"; do
    if [ -x "$d/node" ]; then
        PATH="$d:$PATH"
        export PATH
        break
    fi
done
exec "$ROOT/node_modules/.bin/pi" "$@"
WRAPPER
    chmod 755 "$BIN"
}

if [ -x "$NPM_BIN" ]; then
    [ -x "$BIN" ] || write_wrapper || log "警告：wrapper 生成失败，将回退到裸 npm bin"
    found="$("$NPM_BIN" --version 2>/dev/null | head -1)"
    log "已安装：$NPM_BIN"
    log "启动器：$([ -x "$BIN" ] && echo "$BIN" || echo "（缺失，回退裸 bin）")"
    log "版本：${found:-未知}"
    case "$found" in
        "$PI_VERSION"*) : ;;
        *) log "版本与清单（$PI_VERSION）不同；pi 迭代较快，不阻断，仅提示" ;;
    esac
    exit 0
fi

if [ "$MODE" = "check" ]; then
    log "缺失：$NPM_BIN"
    exit 2
fi

NPM="$(command -v npm 2>/dev/null || true)"
if [ -z "$NPM" ]; then
    for candidate in /opt/node24/bin/npm /usr/local/bin/npm; do
        [ -x "$candidate" ] && NPM="$candidate" && break
    done
fi
if [ -z "$NPM" ]; then
    log "失败：找不到 npm，无法安装内置 agent"
    exit 2
fi

mkdir -p "$DEST" || { log "失败：无法创建 $DEST"; exit 2; }
if [ ! -f "$DEST/package.json" ]; then
    printf '{\n  "name": "robot-autotest-bundled-pi",\n  "private": true,\n  "version": "1.0.0"\n}\n' \
        > "$DEST/package.json"
fi

log "安装 ${PI_PACKAGE}@${PI_VERSION} → $DEST"
if ! ( cd "$DEST" && "$NPM" install --no-audit --no-fund --loglevel=error \
        "${PI_PACKAGE}@${PI_VERSION}" ); then
    log "失败：npm install 未成功"
    exit 2
fi

if [ ! -x "$NPM_BIN" ]; then
    log "失败：安装后仍找不到可执行文件 $NPM_BIN"
    exit 2
fi
write_wrapper || { log "失败：wrapper 生成失败"; exit 2; }
log "完成：$("$NPM_BIN" --version 2>/dev/null | head -1)"
log "启动器：$BIN"
exit 0
