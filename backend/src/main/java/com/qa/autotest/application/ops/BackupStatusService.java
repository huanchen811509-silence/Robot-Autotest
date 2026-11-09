package com.qa.autotest.application.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 备份/恢复演练状态（FR-3.3-10）。
 *
 * <p>读取 {@code scripts/backup-drill.sh} 写出的真实 JSON，不做「有没有备份」的主观判断：
 * 文件不存在就是没有证据，页面与告警都必须显示「未演练」。
 */
@Service
public class BackupStatusService {

    private static final Logger LOG = LoggerFactory.getLogger(BackupStatusService.class);

    /** 演练结果：{@code present=false} 表示没有证据文件。 */
    public record BackupStatus(boolean present, String generatedAt, long rtoSeconds,
                               long rpoSeconds, String verdict, int tables, String database,
                               long ageMinutes) {
        public static BackupStatus missing() {
            return new BackupStatus(false, null, -1, -1, "MISSING", 0, null, -1);
        }

        public boolean stale(long thresholdMinutes) {
            return !present || ageMinutes > thresholdMinutes;
        }
    }

    private final ObjectMapper mapper;
    private final Path statusFile;

    public BackupStatusService(ObjectMapper mapper,
                               @Value("${ops.backup.status-file:docs/evidence/ops/"
                                       + "backup-drill-latest.json}") String statusFile) {
        this.mapper = mapper;
        this.statusFile = Path.of(statusFile);
    }

    public BackupStatus latest() {
        try {
            if (!Files.exists(statusFile)) {
                return BackupStatus.missing();
            }
            JsonNode node = mapper.readTree(Files.readString(statusFile));
            String generated = text(node, "generatedAt");
            Instant when = generated == null ? Files.getLastModifiedTime(statusFile).toInstant()
                    : Instant.parse(generated);
            long age = Math.max(0, (System.currentTimeMillis() - when.toEpochMilli()) / 60_000);
            return new BackupStatus(true, generated, node.path("rtoSeconds").asLong(-1),
                    node.path("rpoSeconds").asLong(-1), text(node, "verdict"),
                    node.path("tables").asInt(0), text(node, "database"), age);
        } catch (Exception broken) {
            LOG.warn("备份演练证据读取失败 path={}", statusFile, broken);
            return BackupStatus.missing();
        }
    }

    /** 供演练脚本/工具写入后立刻刷新页面，避免缓存。 */
    public Optional<String> raw() {
        try {
            return Files.exists(statusFile) ? Optional.of(Files.readString(statusFile))
                    : Optional.empty();
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
