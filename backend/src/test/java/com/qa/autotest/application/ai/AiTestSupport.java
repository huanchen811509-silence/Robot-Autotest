package com.qa.autotest.application.ai;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.AiSessionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** AI 单测公共支架：定位假 CLI、构造 stub 探测、起带 Noop 审计的服务、管理系统属性。 */
final class AiTestSupport {

    private AiTestSupport() {
    }

    /** surefire 的工作目录是 backend/，夹具在仓库根 fixtures/ 下。 */
    static Path fakeCli() {
        Path primary = Path.of("..", "fixtures", "ai", "fake-cli.sh")
                .toAbsolutePath().normalize();
        if (Files.isExecutable(primary)) {
            return primary;
        }
        Path alternative = Path.of("fixtures", "ai", "fake-cli.sh")
                .toAbsolutePath().normalize();
        if (Files.isExecutable(alternative)) {
            return alternative;
        }
        throw new IllegalStateException("假 CLI 夹具不存在或不可执行: " + primary);
    }

    static AiCliProbe stubProbe(boolean available) {
        return new AiCliProbe() {
            @Override
            public boolean available() {
                return available;
            }

            @Override
            public String primaryCli() {
                return "claude";
            }

            @Override
            public Map<String, Object> probe() {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("anyAvailable", available);
                map.put("mode", available ? "NORMAL" : "DEGRADED");
                map.put("maxLevel", available ? "L3" : "L1");
                return map;
            }
        };
    }

    static AiPanelService service(AiCliProbe probe, AiFeatureGate gate,
                                  AiProcessManager processes, AiSessionRepository repository) {
        return new AiPanelService(probe, new ContextPacker(), processes,
                new AuditService(new NoopAuditRepository(), Runnable::run), gate, repository);
    }

    /** 批量设置系统属性并返回还原器；测试一律在 finally / @AfterEach 中还原。 */
    static Runnable applyProperties(Map<String, String> props) {
        Map<String, String> previous = new LinkedHashMap<>();
        props.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        return () -> previous.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }
}
