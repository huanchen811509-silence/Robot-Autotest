#!/usr/bin/env bash
# FR-AI-08 回归夹具：断言父进程必须关闭子进程的 stdin。
#
# 本脚本会一直读 stdin 直到 EOF。若父进程（AiProcessManager）不关 stdin，
# `cat` 会阻塞到 AI_CLI_TIMEOUT_SECONDS 超时，结果表现为 degraded + timedOut=true，
# 而不是这里输出的 STDIN_EOF_OK。真实 codex CLI 就是这个行为：
# 未关 stdin 时只打印 "Reading additional input from stdin..." 然后一直等。
#
# 绝不发起任何真实模型调用，也不读取任何凭据 / API key。
set -u

# 阻塞点：读 stdin 到 EOF。父进程关掉 stdin 后立即返回。
cat > /dev/null || true

printf 'STDIN_EOF_OK\n'
exit 0
