package com.qa.autotest.application.execution.monitor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 取材适配器（FR-v5.3-01 / R3）：把「一行原始文本」翻译成统一事件或命令。
 *
 * <p>这是监视器里<b>唯一</b>允许因内核而不同的地方。换内核只换适配器，
 * 事件 schema、命令 schema、上限与降级策略全部共用——否则「内核无关的监视器」
 * 会退化成「两套监视器共用一个类名」。
 */
public interface KernelAdapter {

    /** 内核标识：{@code robot} / {@code pytest}。 */
    String kernel();

    /** 解析一行标准输出/标准错误；判不出来返回空。 */
    Optional<Parsed> parse(String line, boolean stderr);

    /**
     * 解析结果。{@code command} 非空时表示这行是一条命令（同时也要产 {@code COMMAND} 事件）。
     *
     * @param kind   事件类型
     * @param caseCode 用例标识（拿不到为 null）
     * @param name   名称（用例名/阶段名）
     * @param detail 细节（PASS/FAIL 或错误首行）
     * @param command 命令草稿（可为 null）
     */
    record Parsed(MonitorEvent.Kind kind, String caseCode, String name, String detail,
                  CommandDraft command) {

        static Parsed of(MonitorEvent.Kind kind, String caseCode, String name, String detail) {
            return new Parsed(kind, caseCode, name, detail, null);
        }

        static Parsed command(CommandDraft draft) {
            return new Parsed(MonitorEvent.Kind.COMMAND, null, draft.endpoint(),
                    draft.commandLine(), draft);
        }
    }

    /**
     * 命令草稿。
     *
     * @param endpoint 目标端点（{@code host} 或 {@code host:port}），本机命令为 {@code localhost}
     * @param argv     已分词、未脱敏的 argv
     */
    record CommandDraft(String endpoint, List<String> argv) {

        String commandLine() {
            return String.join(" ", argv);
        }
    }

    // ------------------------------------------------------------ 命令行共用探测

    /** 命令行前缀：证据 LOG 里命令原文的三种常见形态。 */
    Pattern COMMAND_PREFIX = Pattern.compile("^\\s{0,8}(?:\\$|\\+|cmd>|PS>)\\s+(.+)$");

    /** {@code ssh [opts] host 'cmd'}：远端执行。 */
    Pattern SSH = Pattern.compile("^ssh\\s+(.*)$");

    /** {@code ip:port} 或裸主机名/IP 的 endpoint 归一。 */
    Pattern ENDPOINT = Pattern.compile("^([A-Za-z0-9_.\\-]+)(?::(\\d{1,5}))?$");

    /**
     * 通用命令探测（两内核共用）。识别失败返回空——<b>宁可不记，不要瞎记</b>。
     */
    static Optional<CommandDraft> detectCommand(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        Matcher prefixed = COMMAND_PREFIX.matcher(line);
        String body = prefixed.matches() ? prefixed.group(1).trim()
                : (looksLikeBareCommand(line) ? line.trim() : null);
        if (body == null || body.isEmpty()) {
            return Optional.empty();
        }
        return toDraft(ShellWords.split(body));
    }

    /** 没有前缀时，只认「首词是已知工具」的行，避免把普通日志当命令。 */
    private static boolean looksLikeBareCommand(String line) {
        String trimmed = line.trim();
        int space = trimmed.indexOf(' ');
        String head = space < 0 ? trimmed : trimmed.substring(0, space);
        String tool = head.substring(Math.max(head.lastIndexOf('/'), head.lastIndexOf('\\')) + 1)
                .toLowerCase(Locale.ROOT);
        return CommandKind.classify(List.of(tool)) != CommandKind.OTHER
                || "ssh".equals(tool) || "scp".equals(tool) || "sudo".equals(tool);
    }

    /** ssh 剥一层：endpoint 取远端主机，argv 取远端命令。 */
    static Optional<CommandDraft> toDraft(List<String> argv) {
        if (argv.isEmpty()) {
            return Optional.empty();
        }
        if ("sudo".equals(argv.get(0)) && argv.size() > 1) {
            // sudo 只是提权，命令本体在后面
            return toDraft(argv.subList(1, argv.size()));
        }
        if (!"ssh".equals(argv.get(0))) {
            return Optional.of(new CommandDraft("localhost", List.copyOf(argv)));
        }
        String host = null;
        int index = 1;
        for (; index < argv.size(); index++) {
            String arg = argv.get(index);
            if (arg.startsWith("-")) {
                // 带参数的选项（-p 22 / -i key / -l user）要跳过它的值
                if (SSH_OPTIONS_WITH_VALUE.contains(arg)) {
                    index++;
                }
                continue;
            }
            host = arg;
            index++;
            break;
        }
        if (host == null) {
            return Optional.empty();
        }
        List<String> remote = argv.subList(index, argv.size());
        List<String> remoteArgv = remote.isEmpty() ? List.of("ssh", host)
                : (remote.size() == 1 ? ShellWords.split(remote.get(0)) : remote);
        if (remoteArgv.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CommandDraft(endpointOf(host), List.copyOf(remoteArgv)));
    }

    /** {@code user@host:port} → {@code host:port}（用户信息不进 endpoint，属敏感面）。 */
    static String endpointOf(String host) {
        String value = host == null ? "" : host.trim();
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        return value.isEmpty() ? "localhost" : value;
    }

    /** ssh 里「后跟一个值」的选项集合；不在表里的选项当作无值标志。 */
    java.util.Set<String> SSH_OPTIONS_WITH_VALUE =
            java.util.Set.of("-p", "-i", "-l", "-o", "-P", "-F", "-c", "-b", "-D", "-L", "-R");
}
