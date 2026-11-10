package com.qa.autotest.application.host;

import static org.assertj.core.api.Assertions.assertThat;

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
}
