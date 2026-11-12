package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.application.execution.monitor.ExecutionMonitor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 规模化保护（FR-v5.4-06 / D15–D16 / RK-4 / W54-07）。
 *
 * <p>三道闸，各自挡住一类「跑起来才发现」的事故：
 * <ol>
 *   <li><b>单 run node 上限</b>：一次往 200 台机器发 pabot，最先崩的是数据库连接池，
 *       而不是用例本身；限流让人必须先分批，而不是等平台挂掉；</li>
 *   <li><b>证据目录配额</b>：日志与截图是无底洞，一个跑疯的用例能把磁盘写满，
 *       而磁盘满了之后所有 run 都会失败，包括没跑疯的那些；</li>
 *   <li><b>命令条数上限</b>：已在监视器里（{@link ExecutionMonitor#DEFAULT_MAX_COMMANDS}），
 *       这里只做口径统一，不重复实现。</li>
 * </ol>
 *
 * <p><b>超限一律「明确拒绝 + 说清上限与当前值」</b>，不做「静默截断」：
 * 静默截断会让人以为 200 台都跑了、其实只跑了 32 台，那比直接报错危险得多——
 * 报错会被处理，静默截断只会变成一句「上次好像没跑全」。
 */
@Service
public class ScaleGuard {

    private static final Logger log = LoggerFactory.getLogger(ScaleGuard.class);

    public static final String KEY_MAX_NODES = ProjectRunCoordinator.PREFIX + "max_nodes_per_run";
    public static final String KEY_EVIDENCE_QUOTA_MB =
            ProjectRunCoordinator.PREFIX + "evidence_quota_mb";

    /** 默认 32 台：pabot 在这个量级下稳定，再往上要先调连接池。 */
    public static final int DEFAULT_MAX_NODES = 32;
    /** 默认 2 GB。 */
    public static final long DEFAULT_EVIDENCE_QUOTA_MB = 2048;

    private final ProjectSettingRepository settings;

    public ScaleGuard(ProjectSettingRepository settings) {
        this.settings = settings;
    }

    /**
     * node 上限校验。
     *
     * @param requested 这次要用的并行度（等价于 node 数）
     * @throws BusinessException 超限时抛出，消息里带上限与当前值
     */
    public void requireNodes(long projectId, int requested) {
        int limit = maxNodes(projectId);
        if (requested > limit) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "并发（node）数 " + requested + " 超过项目上限 " + limit
                            + "；请分批复跑，或由管理员调整 "
                            + KEY_MAX_NODES + "（调高前先确认数据库连接池与节点数）");
        }
    }

    /** node 上限（配置错或未配置用默认值）。 */
    public int maxNodes(long projectId) {
        return (int) longOr(projectId, KEY_MAX_NODES, DEFAULT_MAX_NODES, 1, 512);
    }

    /** 证据配额（MB）。 */
    public long evidenceQuotaMb(long projectId) {
        return longOr(projectId, KEY_EVIDENCE_QUOTA_MB, DEFAULT_EVIDENCE_QUOTA_MB, 64, 1_048_576);
    }

    /**
     * 证据目录是否已超配额。
     *
     * <p>只读目录大小、不删任何东西：删证据是不可逆操作，必须由人或保留策略执行，
     * 不能由一次执行触发。这里只负责「告诉执行器已经满了」。
     *
     * @return 超限时的说明；未超限返回 null
     */
    public String evidenceOverQuota(long projectId, Path evidenceDir) {
        if (evidenceDir == null || !Files.isDirectory(evidenceDir)) {
            return null;
        }
        long quotaBytes = evidenceQuotaMb(projectId) * 1024L * 1024L;
        long used;
        try (Stream<Path> stream = Files.walk(evidenceDir, 6)) {
            used = stream.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            // 算不出来就不拦：配额是保护措施，不该因为算不出大小而阻塞执行
            log.debug("证据目录大小统计失败 {}：{}", evidenceDir, e.toString());
            return null;
        }
        if (used <= quotaBytes) {
            return null;
        }
        return "证据目录已用 " + (used / 1024 / 1024) + " MB，超过配额 "
                + (quotaBytes / 1024 / 1024) + " MB；请先归档或调高 "
                + KEY_EVIDENCE_QUOTA_MB;
    }

    /** 命令条数上限（与监视器同一口径，避免两处写两个数）。 */
    public int maxCommands() {
        return ExecutionMonitor.DEFAULT_MAX_COMMANDS;
    }

    private long longOr(long projectId, String key, long fallback, long min, long max) {
        Map<String, String> all;
        try {
            all = settings.findAll(projectId, ProjectRunCoordinator.PREFIX);
        } catch (RuntimeException e) {
            return fallback;
        }
        String raw = all.get(key.substring(ProjectRunCoordinator.PREFIX.length()));
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            // 配得离谱（0、负数、超大）一律回默认：这类配置一旦生效，
            // 后果是「所有执行都被拒」或「等于没有上限」，两种都比用默认值更糟
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
