package com.qa.autotest.application.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.EnvironmentGateRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.env.DeviceSnapshot;
import com.qa.autotest.domain.env.DeviceSnapshotItem;
import com.qa.autotest.domain.env.EnvironmentGate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 执行前环境检查服务（v5.15.0 / FR-v5.15-09）。
 *
 * <p>声明才校验：没有 requirements 时直接放行并写留痕；有声明时读**最新一份**按项快照，
 * 不满足抛 409 且逐项点名。这个服务是独立的，不挂在 RunExecutionService 上——
 * 后者在 v5.0 冻结清单里，动它会触发门禁 33 判红，没有必要为一个新能力付这个代价。
 */
@Service
public class EnvironmentGateService {

    public record CheckRequest(String caseKey, String runId,
                               List<EnvironmentGate.Requirement> requirements) {
    }

    public record CheckResult(boolean pass, boolean undeclared, long snapshotId,
                              List<EnvironmentGate.Failure> failures, String note,
                              int notedRows) {
    }

    private final DeviceSnapshotService snapshots;
    private final ProbePackService packs;
    private final EnvironmentGateRepository notes;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public EnvironmentGateService(DeviceSnapshotService snapshots, ProbePackService packs,
                                  EnvironmentGateRepository notes, AuditService audit,
                                  ObjectMapper mapper) {
        this.snapshots = snapshots;
        this.packs = packs;
        this.notes = notes;
        this.audit = audit;
        this.mapper = mapper;
    }

    public CheckResult check(long projectId, CheckRequest request) {
        CheckRequest safe = request == null
                ? new CheckRequest(null, null, List.of()) : request;
        List<DeviceSnapshot> latest = snapshots.list(projectId, 1);
        if (latest.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "环境闸门未通过：无可用设备快照；请先执行一次采集");
        }
        long snapshotId = latest.get(0).id();
        List<EnvironmentGate.Requirement> declared = safe.requirements() == null
                ? List.of() : safe.requirements();
        Map<String, String> facts = packs.factsOf(projectId, snapshotId);
        facts.putAll(gbpsFacts(projectId, snapshotId));
        EnvironmentGate.Verdict verdict = EnvironmentGate.evaluate(declared, facts, snapshotId);
        StringBuilder note = new StringBuilder();
        note.append("caseKey=").append(safe.caseKey() == null ? "-" : safe.caseKey())
                .append("; undeclared=").append(verdict.undeclared())
                .append("; pass=").append(verdict.pass())
                .append("; snapshotId=").append(snapshotId);
        if (!verdict.failures().isEmpty()) {
            for (EnvironmentGate.Failure failure : verdict.failures()) {
                note.append(" | ").append(failure.describe());
            }
        }
        int notedRows = 0;
        if (safe.runId() != null && !safe.runId().isBlank()) {
            notedRows = notes.noteRun(projectId, safe.runId(), note.toString());
        }
        audit.record(verdict.pass() ? "env.gate.pass" : "env.gate.reject", "device_snapshot",
                String.valueOf(snapshotId), note.toString());
        if (!verdict.pass()) {
            throw new BusinessException(ErrorCode.CONFLICT, "环境闸门未通过：" + note);
        }
        return new CheckResult(true, verdict.undeclared(), snapshotId, verdict.failures(),
                note.toString(), notedRows);
    }

    public List<EnvironmentGateRepository.RunNote> notes(long projectId, int limit) {
        return notes.notes(projectId, limit);
    }

    /** 把 ROCE 项里的 rateGbps 最大值提成数值事实，供 MIN_GBPS 判据使用。 */
    private Map<String, String> gbpsFacts(long projectId, long snapshotId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (DeviceSnapshotItem item : snapshots.items(projectId, snapshotId, "ROCE")) {
            if (!item.collected() || item.valueJson() == null) {
                continue;
            }
            Integer max = maxGbps(item.valueJson());
            if (max != null) {
                result.put("ROCE.maxGbps", String.valueOf(max));
            }
        }
        return result;
    }

    private Integer maxGbps(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            return scan(root);
        } catch (Exception ex) {
            return null;
        }
    }

    private Integer scan(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isArray() || node.isObject()) {
            Integer max = null;
            for (JsonNode child : node) {
                Integer value = scan(child);
                if (value != null && (max == null || value > max)) {
                    max = value;
                }
            }
            return max;
        }
        return node.isNumber() ? node.intValue() : null;
    }
}
