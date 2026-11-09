package com.qa.autotest.application.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 在**平台本机**执行只读采集命令（FR-v3.11-01）。
 *
 * <p>能力边界写在最前面，不靠调用方猜：
 * <ul>
 *   <li>只支持 host 为本机（`localhost` / `127.0.0.1` / `::1` / 本机主机名）；</li>
 *   <li>远程主机需要走 SSH + 凭据库解密，本版未接（记 L-311-01），此时返回
 *       {@link HostCommandRunner.Outcome#fail} 并写明原因——采集项会被标成「未采集到」
 *       并带上这句原因，而不是伪造一份本机数据顶上去。</li>
 * </ul>
 *
 * <p>用 `sh -c` 而不是拼 argv：采集命令里有管道与循环（`for ... /sys/class/net/*`），
 * 这些是只读遍历的必要写法；命令全部来自常量表，不接受外部输入拼接，因此不引入注入面。
 */
@Component
public class LocalHostCommandRunner implements HostCommandRunner {

    private static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "::1");

    @Override
    public Outcome run(String host, String command, Duration timeout) {
        if (!isLocal(host)) {
            return Outcome.fail("远程主机采集未接入（host=" + host
                    + "）：本版只支持平台本机，远程需凭据库 + SSH 通道（见 L-311-01）");
        }
        Path out = null;
        Path err = null;
        try {
            out = Files.createTempFile("host-spec-out-", ".log");
            err = Files.createTempFile("host-spec-err-", ".log");
            ProcessBuilder builder = new ProcessBuilder(List.of("sh", "-c", command));
            builder.redirectOutput(out.toFile());
            builder.redirectError(err.toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            String stdout = readQuietly(out);
            String stderr = readQuietly(err);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return Outcome.fail("采集命令超时（>" + timeout.toSeconds() + "s）");
            }
            return new Outcome(process.exitValue(), stdout, stderr);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Outcome.fail("采集被中断");
        } catch (IOException ex) {
            return Outcome.fail("本机命令启动失败：" + ex.getMessage());
        } finally {
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    private static boolean isLocal(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (LOCAL_HOSTS.contains(value)) {
            return true;
        }
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName().toLowerCase(Locale.ROOT);
            return value.equals(hostname) || value.equals(hostname + ".local");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响采集结果
        }
    }
}
