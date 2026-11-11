package com.qa.autotest.application.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SSH 采集通道的边界行为（FR-v4.2-03）。
 *
 * <p>没有真实远端也能验的部分全部在这里：用假 ssh/sshpass 脚本替代真进程，
 * 覆盖「指纹失败要透出原因」「超时杀进程」「输出截断」「提权拒绝」「口令不进 argv」
 * 「私钥临时文件用完即删」「参数注入」。真实远端的那一次采集记 W42-03 NOT-RUN。
 */
class SshHostCommandRunnerTest {

    @TempDir
    Path temp;

    private static final String HOST = "10.20.30.40";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private Path script(String name, String body) throws Exception {
        Path file = temp.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    private HostAccess access(String secret) {
        return new HostAccess(1L, "robot", 7L, secret);
    }

    /** 假 sshpass：跳过 `-e`，把剩下的 argv（ssh + 选项）原样执行。 */
    private Path sshpass() throws Exception {
        return script("sshpass-exec.sh", "shift\nexec \"$@\"");
    }

    @Test
    @DisplayName("提权命令直接拒绝，不启动任何进程")
    void 拒绝提权命令() throws Exception {
        Path marker = temp.resolve("called.marker");
        Path ssh = script("ssh-sudo.sh", "touch " + marker + "\nexit 0");
        SshHostCommandRunner runner = new SshHostCommandRunner(ssh.toString(), "/bin/false",
                temp.resolve("known_hosts"));

        HostCommandRunner.Outcome outcome = runner.run(HOST, "sudo lscpu", TIMEOUT, access("pw"));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).contains("提权");
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    @DisplayName("主机/账号不能借 -o 参数注入")
    void 拒绝参数注入() throws Exception {
        SshHostCommandRunner runner = new SshHostCommandRunner("/bin/true", "/bin/true",
                temp.resolve("known_hosts"));

        assertThat(runner.run("-oProxyCommand=id", "uname -a", TIMEOUT, access("pw")).reason())
                .contains("非法主机名");
        assertThat(runner.run(HOST, "uname -a", TIMEOUT,
                new HostAccess(1L, "-oProxyCommand=id", 7L, "pw")).reason())
                .contains("非法 SSH 账号");
    }

    @Test
    @DisplayName("主机指纹不符时说清是准入问题，而不是含糊的连接失败")
    void 指纹失败要透出原因() throws Exception {
        Path ssh = script("ssh-badkey.sh", "echo 'Host key verification failed.' >&2\nexit 255");
        SshHostCommandRunner runner = new SshHostCommandRunner(ssh.toString(), sshpass().toString(),
                temp.resolve("known_hosts"));

        HostCommandRunner.Outcome outcome = runner.run(HOST, "uname -a", TIMEOUT, access("pw"));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).contains("指纹校验失败").contains("Host key verification failed");
    }

    @Test
    @DisplayName("超时杀掉远端进程，不无限等待")
    void 超时杀进程() throws Exception {
        Path ssh = script("ssh-slow.sh", "sleep 3\nexit 0");
        SshHostCommandRunner runner = new SshHostCommandRunner(ssh.toString(), sshpass().toString(),
                temp.resolve("known_hosts"));

        long started = System.nanoTime();
        HostCommandRunner.Outcome outcome = runner.run(HOST, "uname -a", Duration.ofMillis(300),
                access("pw"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).contains("超时");
        assertThat(elapsedMs).isLessThan(2500L);
    }

    @Test
    @DisplayName("输出超过上限时截断并标注，不假称采全")
    void 输出超限截断() throws Exception {
        Path ssh = script("ssh-big.sh",
                "awk 'BEGIN{for(i=0;i<300000;i++)printf \"a\"}'\nexit 0");
        SshHostCommandRunner runner = new SshHostCommandRunner(ssh.toString(), sshpass().toString(),
                temp.resolve("known_hosts"));

        HostCommandRunner.Outcome outcome = runner.run(HOST, "df -h", TIMEOUT, access("pw"));

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.stdout()).contains("已截断");
        assertThat(outcome.stdout().length()).isLessThanOrEqualTo(
                SshHostCommandRunner.MAX_OUTPUT_BYTES + 80);
    }

    @Test
    @DisplayName("口令只走 SSHPASS 环境变量，不进 argv")
    void 口令不进argv() throws Exception {
        Path captured = temp.resolve("argv.txt");
        Path env = temp.resolve("sshpass-env.txt");
        Path sshpass = script("sshpass-fake.sh",
                "printf '%s\\n' \"$@\" > " + captured + "\nprintf '%s' \"$SSHPASS\" > " + env
                        + "\nexit 0");
        SshHostCommandRunner runner = new SshHostCommandRunner("/bin/false", sshpass.toString(),
                temp.resolve("known_hosts"));

        String secret = "s3cret-with space";
        HostCommandRunner.Outcome outcome = runner.run(HOST, "uname -a", TIMEOUT, access(secret));

        assertThat(outcome.ok()).isTrue();
        List<String> argv = Files.readAllLines(captured);
        assertThat(argv).doesNotContain(secret);
        assertThat(String.join(" ", argv)).doesNotContain(secret);
        assertThat(Files.readString(env)).isEqualTo(secret);
    }

    @Test
    @DisplayName("私钥写临时文件（0600）且执行后删除")
    void 私钥临时文件用完即删() throws Exception {
        Path copy = temp.resolve("key-copy.pem");
        Path keyPathFile = temp.resolve("key-path.txt");
        Path ssh = script("ssh-key.sh",
                "prev=''\nfor arg in \"$@\"; do\n"
                        + "  if [ \"$prev\" = '-i' ]; then echo \"$arg\" > " + keyPathFile + "; cp \"$arg\" " + copy
                        + "; fi\n  prev=\"$arg\"\ndone\necho ok\nexit 0");
        SshHostCommandRunner runner = new SshHostCommandRunner(ssh.toString(), "/bin/false",
                temp.resolve("known_hosts"));

        String key = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAABBBB\n-----END OPENSSH PRIVATE KEY-----";
        HostCommandRunner.Outcome outcome = runner.run(HOST, "uname -a", TIMEOUT,
                new HostAccess(1L, "robot", 7L, key));

        assertThat(outcome.ok()).isTrue();
        assertThat(Files.readString(copy)).contains("BEGIN OPENSSH PRIVATE KEY");
        Path usedKey = Path.of(Files.readString(keyPathFile).trim());
        assertThat(Files.exists(usedKey)).as("临时私钥用完应删除").isFalse();
    }

    @Test
    @DisplayName("路由：带凭据走 SSH，不带凭据回落到本机执行器")
    void 路由按凭据分通道() throws Exception {
        Path called = temp.resolve("ssh-called.txt");
        Path ssh = script("ssh-route.sh", "echo called > " + called + "\necho ok\nexit 0");
        HostCommandRunnerRouter router = new HostCommandRunnerRouter(new LocalHostCommandRunner(),
                new SshHostCommandRunner(ssh.toString(), sshpass().toString(),
                        temp.resolve("known_hosts")));

        assertThat(router.run(HOST, "uname -a", TIMEOUT, null).reason()).contains("远程主机采集未接入");
        assertThat(router.run(HOST, "uname -a", TIMEOUT, access("pw")).ok()).isTrue();
        assertThat(Files.readString(called).trim()).isEqualTo("called");
        // 本机 host 即便误带凭据也不该走 SSH
        Files.deleteIfExists(called);
        assertThat(router.run("localhost", "uname -a", TIMEOUT, access("pw")).ok()).isTrue();
        assertThat(Files.exists(called)).isFalse();
    }

    // ---------------------------------------------------------------- v4.12.0 / FR-v4.12-04

    @Test
    @DisplayName("连接参数逐项变成独立的 -o，不做 shell 字符串拼接")
    void connectOptionsBecomeSeparateOFlags() throws Exception {
        Path argvFile = temp.resolve("argv.txt");
        Path ssh = script("ssh-opt.sh", "printf '%s\\n' \"$@\" > " + argvFile + "\necho ok\nexit 0");
        SshHostCommandRunner runner = new SshHostCommandRunner(ssh.toString(),
                sshpass().toString(), temp.resolve("known_hosts"));

        HostAccess withOptions = new HostAccess(1L, "ehtadmin", 9L, "pw", "network",
                "KexAlgorithms=+diffie-hellman-group14-sha1,+diffie-hellman-group1-sha1"
                        + ";HostKeyAlgorithms=+ssh-rsa");
        assertThat(runner.run(HOST, "show version", TIMEOUT, withOptions).ok()).isTrue();

        List<String> argv = Files.readAllLines(argvFile);
        assertThat(argv).contains(
                "KexAlgorithms=+diffie-hellman-group14-sha1,+diffie-hellman-group1-sha1");
        assertThat(argv).contains("HostKeyAlgorithms=+ssh-rsa");
        // 每个选项都必须紧跟在独立的 -o 后面，而不是粘成一个 -oKey=Value 或一长串
        int index = argv.indexOf("HostKeyAlgorithms=+ssh-rsa");
        assertThat(index).isPositive();
        assertThat(argv.get(index - 1)).isEqualTo("-o");
    }

    @Test
    @DisplayName("连接参数非法（危险键）：整串拒绝，进程根本不启动")
    void invalidConnectOptionsRejected() throws Exception {
        Path called = temp.resolve("called-opt.txt");
        Path ssh = script("ssh-opt-bad.sh", "echo called > " + called + "\nexit 0");
        SshHostCommandRunner runner = new SshHostCommandRunner(ssh.toString(),
                sshpass().toString(), temp.resolve("known_hosts"));

        HostAccess bad = new HostAccess(1L, "robot", 9L, "pw", "ssh", "ProxyCommand=nc evil 22");
        HostCommandRunner.Outcome outcome = runner.run(HOST, "uname -a", TIMEOUT, bad);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).contains("连接参数被拒绝");
        assertThat(Files.exists(called)).isFalse();
    }

    @Test
    @DisplayName("参数解析：三种分隔写法都认；危险键与非法格式都拒")
    void parseExtraOptionsRules() {
        assertThat(SshHostCommandRunner.parseExtraOptions(null)).isEmpty();
        assertThat(SshHostCommandRunner.parseExtraOptions("   ")).isEmpty();
        // 单条 + 值内逗号（KEX 算法列表本身是逗号分隔的，不能被当成分隔符）
        assertThat(SshHostCommandRunner.parseExtraOptions("KexAlgorithms=+a,+b"))
                .containsExactly("KexAlgorithms=+a,+b");
        // 分号分隔
        assertThat(SshHostCommandRunner.parseExtraOptions(
                "KexAlgorithms=+a;HostKeyAlgorithms=+ssh-rsa"))
                .containsExactly("KexAlgorithms=+a", "HostKeyAlgorithms=+ssh-rsa");
        // 逗号 + 下一个 Key=
        assertThat(SshHostCommandRunner.parseExtraOptions(
                "KexAlgorithms=+a,HostKeyAlgorithms=+ssh-rsa"))
                .containsExactly("KexAlgorithms=+a", "HostKeyAlgorithms=+ssh-rsa");
        assertThatThrownBy(() -> SshHostCommandRunner.parseExtraOptions("ProxyCommand=nc x 22"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshHostCommandRunner.parseExtraOptions("no-equals-sign"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 现场真实串（v4.12.0 W-D 取证用）：4 台交换机的 connect_options 就是这个值。
     *
     * <p>它同时含「值内逗号」与「分号分隔」两种形态，是最容易切错的一段：
     * 按逗号硬切会把 `diffie-hellman-group1-sha1` 切成独立一项，直接判格式非法，
     * 现场表现就是「凭据看着没问题但一台都连不上」。
     */
    @Test
    @DisplayName("参数解析：现场交换机真实参数串切成 3 条，值内逗号保留")
    void parseRealSwitchOptions() {
        String real = "KexAlgorithms=+diffie-hellman-group14-sha1,diffie-hellman-group1-sha1"
                + ";HostKeyAlgorithms=+ssh-rsa;PubkeyAcceptedAlgorithms=+ssh-rsa";
        assertThat(SshHostCommandRunner.parseExtraOptions(real)).containsExactly(
                "KexAlgorithms=+diffie-hellman-group14-sha1,diffie-hellman-group1-sha1",
                "HostKeyAlgorithms=+ssh-rsa",
                "PubkeyAcceptedAlgorithms=+ssh-rsa");
    }

    // ---------------------------------------------------------------- v4.12.0 W-D 现场缺陷回归

    /**
     * 口令模式**必须**是 `BatchMode=no`。
     *
     * <p>回归自现场事故：8 台 Linux + 4 台交换机同时报
     * `Permission denied (publickey,password)`，而用 sshpass 手敲同样参数直连全部 PASS。
     * 差异只有 `BatchMode=yes` —— 它关闭全部交互式提问，sshpass 等不到 `password:`
     * 提示符就没有口令可喂，ssh 走完公钥一轮就退出。
     * 这条断言就是防止有人「为了防卡死」把 BatchMode 改回 yes。
     */
    @Test
    @DisplayName("口令模式放开 BatchMode，给 sshpass 留出提示符")
    void passwordModeAllowsInteractivePrompt() throws Exception {
        Path argvFile = temp.resolve("argv-pw-mode.txt");
        Path ssh = script("ssh-pw-mode.sh", "printf '%s\\n' \"$@\" > " + argvFile + "\necho ok\nexit 0");
        SshHostCommandRunner runner = new SshHostCommandRunner(ssh.toString(),
                sshpass().toString(), temp.resolve("known_hosts"));

        assertThat(runner.run(HOST, "uname -a", TIMEOUT, access("pw")).ok()).isTrue();

        List<String> argv = Files.readAllLines(argvFile);
        assertThat(argv).contains("BatchMode=no");
        assertThat(argv).doesNotContain("BatchMode=yes");
        assertThat(argv).contains("PreferredAuthentications=password,keyboard-interactive");
        // 只问一次口令：错就快速失败，不把批量采集拖成「每台等三遍」
        assertThat(argv).contains("NumberOfPasswordPrompts=1");
        // 指纹校验不能因为放开交互而松动
        assertThat(argv).contains("StrictHostKeyChecking=yes");
    }

    @Test
    @DisplayName("私钥模式保持 BatchMode=yes，且不引入口令相关参数")
    void keyModeKeepsBatchModeYes() throws Exception {
        Path argvFile = temp.resolve("argv-key-mode.txt");
        Path ssh = script("ssh-key-mode.sh", "printf '%s\\n' \"$@\" > " + argvFile + "\necho ok\nexit 0");
        SshHostCommandRunner runner = new SshHostCommandRunner(ssh.toString(), "/bin/false",
                temp.resolve("known_hosts"));
        String key = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----";

        assertThat(runner.run(HOST, "uname -a", TIMEOUT,
                new HostAccess(1L, "robot", 7L, key)).ok()).isTrue();

        List<String> argv = Files.readAllLines(argvFile);
        assertThat(argv).contains("BatchMode=yes", "PreferredAuthentications=publickey");
        assertThat(argv).doesNotContain("BatchMode=no", "NumberOfPasswordPrompts=1");
    }
}
