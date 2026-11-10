package com.qa.autotest.application.host;

import java.time.Duration;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 采集通道路由（FR-v4.2-03）：本机走 {@link LocalHostCommandRunner}，远程走 {@link SshHostCommandRunner}。
 *
 * <p>路由判据只看「有没有访问材料」，不看 host 长什么样：服务层已经保证本机不带凭据、
 * 远程必带线索（credentialId）。这样即便有人把 127.0.0.1 写成别的名字，也不会误走 SSH。
 *
 * <p>加 {@code @Primary} 是为了让注入 {@link HostCommandRunner} 的地方（采集器、离线夹具）
 * 仍然只拿到一个默认实现，不用到处写 {@code @Qualifier}。
 */
@Component
@Primary
public class HostCommandRunnerRouter implements HostCommandRunner {

    private final LocalHostCommandRunner local;
    private final SshHostCommandRunner remote;

    public HostCommandRunnerRouter(LocalHostCommandRunner local, SshHostCommandRunner remote) {
        this.local = local;
        this.remote = remote;
    }

    @Override
    public Outcome run(String host, String command, Duration timeout) {
        return run(host, command, timeout, null);
    }

    @Override
    public Outcome run(String host, String command, Duration timeout, HostAccess access) {
        if (access != null && !LocalHostCommandRunner.isLocal(host)) {
            return remote.run(host, command, timeout, access);
        }
        return local.run(host, command, timeout);
    }
}
