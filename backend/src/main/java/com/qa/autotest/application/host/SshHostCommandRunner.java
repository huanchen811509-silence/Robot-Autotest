package com.qa.autotest.application.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 远程主机只读采集（FR-v4.2-03）。
 *
 * <p>安全边界写在代码里，不靠调用方自觉：
 * <ul>
 *   <li><b>指纹必校验</b>——{@code StrictHostKeyChecking=yes} + 独立 known_hosts，指纹对不上就失败，
 *       不会静默接受新主机（W42-00 的准入记录就是往这个文件里写指纹）；</li>
 *   <li><b>口令不进 argv</b>——口令走 {@code sshpass -e}（读环境变量），私钥写临时文件（0600）且用完即删；</li>
 *   <li><b>不提权</b>——命令里出现 sudo/su/doas 直接拒绝，采集清单本身也只有只读命令；</li>
 *   <li><b>主机/账号不可注入</b>——host、username 都先过字符白名单，排除 `-oProxyCommand=...` 这类参数注入；</li>
 *   <li><b>输出有上限、命令有超时</b>——防止远端一个 `cat 大文件` 把平台内存拖死。</li>
 * </ul>
 */
@Component
public class SshHostCommandRunner implements HostCommandRunner {

    /** 单条命令的 stdout 上限；超了截断并注明，不假装采全了。 */
    static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    /** 提权动作：采集命令全是只读，出现这些词只能是恶意/误配。 */
    private static final Pattern PRIVILEGE = Pattern.compile("(?i)(?:^|[\\s;&|])(sudo|su|doas)(?:\\s|$)");

    /** 主机名/IP：允许 IPv6 的冒号，不允许以 `-` 开头（那会被 ssh 当选项）。 */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._:-]{1,255}");

    /** SSH 账号：允许 username 里的常见字符，同样不允许 `-` 开头。 */
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._-]{0,63}");

    /**
     * 附加参数的单项形态（v4.12.0 / FR-v4.12-04）：`Key=Value`。
     *
     * <p>值里允许 `+,:@=/[]-`——`KexAlgorithms=+diffie-hellman-group14-sha1,+group1-sha1`
     * 这种「加号前缀 + 逗号分隔算法列表」是 OpenSSH 的既定写法，不能当成分隔符切掉。
     */
    private static final Pattern EXTRA_OPTION =
            Pattern.compile("[A-Za-z][A-Za-z0-9]{0,31}=[A-Za-z0-9+.,:@=_/\\[\\]-]{0,128}");

    /**
     * 附加参数按「分号」或「逗号 + 下一个 Key=」切分。
     *
     * <p>不能简单按逗号切：KEX 算法列表本身就是逗号分隔的，切完会把
     * `+diffie-hellman-group1-sha1` 当成一个独立选项而报格式错。
     */
    private static final Pattern EXTRA_SPLIT =
            Pattern.compile(";|,(?=[A-Za-z][A-Za-z0-9]{0,31}=)");

    /** 这些键会改变连接目标或引入命令执行，禁止由数据行覆盖。 */
    private static final java.util.Set<String> FORBIDDEN_KEYS = java.util.Set.of(
            "proxycommand", "localcommand", "permitlocalcommand", "identityfile",
            "knownhostscommand", "match", "include", "hostname", "port", "user", "proxyjump");

    private final String sshBinary;
    private final String sshpassBinary;
    private final Path knownHosts;

    public SshHostCommandRunner() {
        this(System.getenv().getOrDefault("HOST_INVENTORY_SSH", "ssh"),
                System.getenv().getOrDefault("HOST_INVENTORY_SSHPASS", "sshpass"),
                Path.of(System.getenv().getOrDefault("HOST_INVENTORY_KNOWN_HOSTS",
                        System.getProperty("user.home") + "/.ssh/known_hosts")));
    }

    SshHostCommandRunner(String sshBinary, String sshpassBinary, Path knownHosts) {
        this.sshBinary = sshBinary;
        this.sshpassBinary = sshpassBinary;
        this.knownHosts = knownHosts;
    }

    @Override
    public Outcome run(String host, String command, Duration timeout) {
        return run(host, command, timeout, null);
    }

    @Override
    public Outcome run(String host, String command, Duration timeout, HostAccess access) {
        if (access == null || access.secret() == null || access.secret().isBlank()) {
            return Outcome.fail("远程主机采集缺少可用凭据（需 CREDENTIAL:USE 且已配置 SSH 口令或私钥）");
        }
        if (host == null || !HOST.matcher(host).matches() || host.startsWith("-")) {
            return Outcome.fail("非法主机名：只接受 IP/主机名，不允许以 - 开头（防参数注入）");
        }
        String user = access.username() == null ? "" : access.username().trim();
        if (!USER.matcher(user).matches()) {
            return Outcome.fail("非法 SSH 账号：只接受字母/数字/下划线/点/短横线，且不能以 - 开头");
        }
        if (command == null || command.isBlank()) {
            return Outcome.fail("采集命令为空");
        }
        if (PRIVILEGE.matcher(command).find()) {
            return Outcome.fail("采集命令包含提权动作（sudo/su/doas），已拒绝执行");
        }

        boolean keyMode = access.secret().stripLeading().startsWith("-----BEGIN");
        List<String> extra;
        try {
            extra = parseExtraOptions(access.connectOptions());
        } catch (IllegalArgumentException ex) {
            return Outcome.fail("连接参数被拒绝：" + ex.getMessage());
        }
        Path keyFile = null;
        Path out = null;
        Path err = null;
        try {
            List<String> argv = new ArrayList<>();
            if (!keyMode) {
                // -e：口令从 SSHPASS 环境变量读，不出现在 argv（ps 看不到）
                argv.add(sshpassBinary);
                argv.add("-e");
            }
            argv.add(sshBinary);
            argv.add("-F");
            argv.add("/dev/null");
            argv.add("-o");
            // 口令模式**不能**开 BatchMode：它关闭全部交互式提问，sshpass 没有
            // `password:` 提示可喂就没有口令可送，ssh 只剩公钥一轮然后报
            // `Permission denied (publickey,password)`。
            // 现场 8 台 Linux + 4 台交换机全栽在这一个开关上（sshpass 手敲同参数直连却全 PASS）。
            // 私钥模式相反：本就无需任何提问，BatchMode=yes 可挡住「未知主机是否继续」之类阻塞。
            argv.add(keyMode ? "BatchMode=yes" : "BatchMode=no");
            argv.add("-o");
            argv.add("StrictHostKeyChecking=yes");
            argv.add("-o");
            argv.add("UserKnownHostsFile=" + knownHosts);
            argv.add("-o");
            argv.add("ConnectTimeout=" + connectTimeout(timeout));
            argv.add("-o");
            argv.add("IdentitiesOnly=yes");
            if (keyMode) {
                keyFile = writeKey(access.secret());
                argv.add("-o");
                argv.add("PreferredAuthentications=publickey");
                argv.add("-i");
                argv.add(keyFile.toString());
            } else {
                argv.add("-o");
                argv.add("PreferredAuthentications=password,keyboard-interactive");
                // 只问一次：口令错就立刻失败，不要连问三遍把批量采集拖成分钟级。
                // sshpass 喂一次、ssh 再要第二次时 sshpass 已退出，多问只会白等。
                argv.add("-o");
                argv.add("NumberOfPasswordPrompts=1");
            }
            // 按凭据配置追加（老交换机只支持 diffie-hellman-group14-sha1 / ssh-rsa）。
            // 逐项转成独立的 `-o Key=Value`，绝不做 shell 字符串拼接。
            for (String option : extra) {
                argv.add("-o");
                argv.add(option);
            }
            argv.add(user + "@" + host);
            argv.add(command);

            out = Files.createTempFile("ssh-host-out-", ".log");
            err = Files.createTempFile("ssh-host-err-", ".log");
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectOutput(out.toFile());
            builder.redirectError(err.toFile());
            if (!keyMode) {
                builder.environment().put("SSHPASS", access.secret());
            }
            builder.environment().put("LC_ALL", "C");
            Process process = builder.start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            String stdout = readCapped(out);
            String stderr = readCapped(err);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return Outcome.fail("远程采集超时（>" + timeout.toSeconds() + "s，host=" + host + "）");
            }
            if (isHostKeyFailure(stderr)) {
                return new Outcome(process.exitValue(), stdout,
                        "主机指纹校验失败（StrictHostKeyChecking=yes，known_hosts=" + knownHosts
                                + "）：请先走准入流程登记指纹；原始输出：" + firstLine(stderr));
            }
            return new Outcome(process.exitValue(), stdout, stderr);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Outcome.fail("远程采集被中断");
        } catch (IOException ex) {
            return Outcome.fail("SSH 通道启动失败：" + ex.getClass().getSimpleName() + "（" + ex.getMessage() + "）");
        } finally {
            deleteQuietly(keyFile);
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    /**
     * 把凭据里的连接参数串拆成 {@code -o} 项（白名单校验，纯函数可离线单测）。
     *
     * <p>三种输入都合法：`KexAlgorithms=+a,+b`（只一条，值内有逗号）、
     * `KexAlgorithms=+a;HostKeyAlgorithms=+ssh-rsa`（分号分隔）、
     * `KexAlgorithms=+a,HostKeyAlgorithms=+ssh-rsa`（逗号 + 下一个 Key=）。
     *
     * <p>非法项**直接拒绝整串**而不是跳过：静默丢弃会让「明明配了参数却还连不上」
     * 变成无法解释的谜案，而采集报告里只会留下一个语焉不详的握手失败。
     */
    static List<String> parseExtraOptions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String piece : EXTRA_SPLIT.split(raw)) {
            String item = piece == null ? "" : piece.trim();
            if (item.isEmpty()) {
                continue;
            }
            if (!EXTRA_OPTION.matcher(item).matches()) {
                throw new IllegalArgumentException("格式非法（应为 Key=Value）：" + item);
            }
            String key = item.substring(0, item.indexOf('=')).toLowerCase(Locale.ROOT);
            if (FORBIDDEN_KEYS.contains(key)) {
                throw new IllegalArgumentException("不允许覆盖该键：" + key);
            }
            result.add(item);
        }
        return result;
    }

    private static int connectTimeout(Duration timeout) {        long seconds = timeout == null ? CONNECT_TIMEOUT_SECONDS : timeout.toSeconds();
        return (int) Math.max(1, Math.min(CONNECT_TIMEOUT_SECONDS, seconds));
    }

    private static boolean isHostKeyFailure(String stderr) {
        String text = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        return text.contains("host key verification failed")
                || text.contains("remote host identification has changed")
                || text.contains("no rsa host key is known");
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        return text.strip().lines().findFirst().orElse("");
    }

    private static Path writeKey(String secret) throws IOException {
        Path key = Files.createTempFile("host-inv-key-", ".pem");
        String content = secret.endsWith("\n") ? secret : secret + "\n";
        Files.writeString(key, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统（例如本地 Windows 调试）退化为默认权限，生产跑在 Linux 上
        }
        return key;
    }

    /** 读临时文件，超过上限就截断并标注——宁可让人看到「截断了」，也不要假装采全。 */
    private static String readCapped(Path path) throws IOException {
        long size = Files.size(path);
        int limit = (int) Math.min(size, MAX_OUTPUT_BYTES);
        byte[] buf;
        try (InputStream in = Files.newInputStream(path)) {
            buf = in.readNBytes(limit);
        }
        String text = new String(buf, StandardCharsets.UTF_8);
        return size > MAX_OUTPUT_BYTES
                ? text + "\n[平台] 输出超过 " + MAX_OUTPUT_BYTES + " 字节，已截断"
                : text;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件残留不影响采集结论
        }
    }
}
