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
            argv.add("BatchMode=yes");
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

    private static int connectTimeout(Duration timeout) {
        long seconds = timeout == null ? CONNECT_TIMEOUT_SECONDS : timeout.toSeconds();
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
