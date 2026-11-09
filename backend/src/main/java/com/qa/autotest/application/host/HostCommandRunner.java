package com.qa.autotest.application.host;

import java.time.Duration;

/**
 * 主机命令执行口（FR-v3.11-01）。
 *
 * <p>抽成接口是为了让采集器**能离线验**：判据「离线夹具验证解析」要求不连真机就能证明
 * 10 类解析逻辑正确，所以采集器只依赖本接口，测试注入夹具输出即可。
 *
 * <p>采集命令一律只读（白名单见 {@link HostInventoryCollector}），本接口不做任何改写，
 * 也不接受调用方拼接可变参数——命令是常量表里抄出来的，不接受外部输入。
 */
public interface HostCommandRunner {

    /**
     * @param host    目标主机（IP 或主机名）
     * @param command 只读命令原文（白名单内常量）
     */
    Outcome run(String host, String command, Duration timeout);

    /**
     * @param exitCode 进程退出码；-1 表示没跑起来（连接失败、超时等）
     * @param stderr   失败原因；成功时可为空
     */
    record Outcome(int exitCode, String stdout, String stderr) {

        public boolean ok() {
            return exitCode == 0;
        }

        /** 给用户看的一句话原因：优先 stderr 首行，其次退出码。 */
        public String reason() {
            String err = stderr == null ? "" : stderr.strip();
            if (!err.isEmpty()) {
                String first = err.lines().findFirst().orElse(err);
                return first.length() > 200 ? first.substring(0, 200) + "…" : first;
            }
            return "命令退出码 " + exitCode + "（无 stderr 输出）";
        }

        public static Outcome ok(String stdout) {
            return new Outcome(0, stdout, "");
        }

        public static Outcome fail(String reason) {
            return new Outcome(-1, "", reason);
        }
    }
}
