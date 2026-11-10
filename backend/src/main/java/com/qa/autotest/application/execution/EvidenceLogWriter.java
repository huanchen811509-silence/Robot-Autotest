package com.qa.autotest.application.execution;

import com.qa.autotest.common.security.SecretRedactor;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * EVID-02：证据 LOG 生成器（可复用）。
 *
 * <p>一轮证据固定五要素：{@code [hostname]} + {@code #步骤N说明} + 命令原文 +
 * stdout/stderr + 真实退出码。退出码必须来自真实进程（{@link ProcessBuilder} 起无害命令后
 * 取 {@link Process#exitValue()}），不许写死常量。任一要素缺失时输出里显式写
 * {@code missing}，不静默补齐。整段文本经 {@link #scrub} 脱敏，凭据不进 LOG。
 */
public class EvidenceLogWriter {

    private static final String MISSING = "missing";

    /** 真实进程采样：ProcessBuilder 起命令，waitFor 后取 exitValue()。 */
    public static Captured capture(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).start();
        String stdout = readAll(process.getInputStream());
        String stderr = readAll(process.getErrorStream());
        process.waitFor();
        int exitCode = process.exitValue();   // 真实退出码，来自真实进程
        return new Captured(exitCode, stdout, stderr);
    }

    private static String readAll(InputStream in) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 脱敏：把凭据值替换为占位，避免 LOG 写入凭据。
     *
     * <p>规则统一住在 {@link SecretRedactor}（FR-v4.0-01）：运行日志 / 控制台 / 报告
     * 使用同一份实现，避免三处正则各自漂移导致某条路径漏脱敏。
     */
    public static String scrub(String text) {
        if (text == null) {
            return null;
        }
        return SecretRedactor.redact(text);
    }

    /** 生成一段可归档文本：五要素齐全；缺任一要素显式标 missing。 */
    public String write(String hostname, int stepNo, String stepDescription,
                        List<String> command, Captured captured) {
        StringBuilder out = new StringBuilder();
        out.append('[').append(field(hostname)).append(']').append('\n');
        out.append("#步骤").append(stepNo).append("说明 ").append(field(stepDescription)).append('\n');
        out.append("$ ").append(field(joinCommand(command))).append('\n');
        out.append("--- stdout ---\n")
                .append(block(captured == null ? null : captured.stdout())).append('\n');
        out.append("--- stderr ---\n")
                .append(block(captured == null ? null : captured.stderr())).append('\n');
        out.append("exit_code=")
                .append(captured == null ? MISSING : captured.exitCode()).append('\n');
        return scrub(out.toString());
    }

    private static String field(String value) {
        return value == null || value.isBlank() ? MISSING : value;
    }

    private static String block(String value) {
        return value == null || value.isBlank() ? MISSING : value.stripTrailing();
    }

    private static String joinCommand(List<String> command) {
        return command == null || command.isEmpty() ? "" : String.join(" ", command);
    }

    /** 一次真实进程的采样结果（不可变）。 */
    public record Captured(int exitCode, String stdout, String stderr) {
    }
}
