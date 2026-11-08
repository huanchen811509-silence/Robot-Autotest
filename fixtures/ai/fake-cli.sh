#!/usr/bin/env bash
# FR-AI 假 CLI 夹具：由 AI_CLI_PATH 注入后经 ProcessBuilder 真起本脚本。
# 用环境变量控制 stdout / stderr / 退出码 / 睡眠秒数，输出固定可断言的字符串。
# 绝不发起任何真实模型调用，也不读取任何凭据 / API key。

set -u

sleep_s="${FAKE_CLI_SLEEP:-0}"
if [ "$sleep_s" != "0" ]; then
  sleep "$sleep_s"
fi

# 从 argv 中提取提示词（-p / --message 的下一个参数），生成可断言的 prompt 摘要。
prompt=""
argc=$#
i=1
while [ "$i" -le "$argc" ]; do
  arg="${!i}"
  if [ "$arg" = "-p" ] || [ "$arg" = "--message" ]; then
    j=$((i + 1))
    if [ "$j" -le "$argc" ]; then
      prompt="${!j}"
    fi
    break
  fi
  i=$((i + 1))
done
if [ -z "$prompt" ]; then
  prompt="$*"
fi
summary="${prompt:0:48}"

out="${FAKE_CLI_STDOUT:-FAKE_CLI_OK:${summary}}"
err="${FAKE_CLI_STDERR:-}"
code="${FAKE_CLI_EXIT:-0}"

printf '%s\n' "$out"
if [ -n "$err" ]; then
  printf '%s\n' "$err" >&2
fi
exit "$code"
