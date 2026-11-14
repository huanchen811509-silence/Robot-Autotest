package com.qa.autotest.application.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * 执行选择冻结快照（W57-27 / W57-28）：freeze / recomputeSha / consume。
 *
 * <p>摘要公式：{@code sha256(projectId|mode|setIds|caseIds|canonicalFilter|caseCount)}，
 * 与 V11_62 迁移注释一致；setIds/caseIds/filter 都先规范化（排序 / 定值），因此同输入同 sha。
 * 唯一键 {@code uk_execution_selection_sha} 命中未消费行则复用；{@code consumed_at} 非空即拒绝复用。
 *
 * <p>纯逻辑 + {@link SnapshotStore} 抽象，单测用内存 fake，不连真实 DB。
 */
@Service
public class SelectionSnapshotService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 持久化抽象：所有方法都带 projectId 作用域。 */
    public interface SnapshotStore {
        Optional<SnapshotRow> findBySha(long projectId, String sha);

        Optional<SnapshotRow> findById(long projectId, long id);

        long insert(SnapshotRow draft);

        /** WHERE consumed_at IS NULL，返回更新行数；0 表示被并发抢先。 */
        int markConsumed(long projectId, long id, Instant at, Long by);
    }

    public record SnapshotRow(Long id,
                              long projectId,
                              String selectionSha,
                              String mode,
                              String setIdsJson,
                              String caseIdsJson,
                              String filterJson,
                              int caseCount,
                              Instant consumedAt,
                              Instant createdAt) {
    }

    /** 冻结结果：reused=true 表示命中唯一键复用了未消费的既有快照。 */
    public record Frozen(long snapshotId,
                         String selectionSha256,
                         int caseCount,
                         List<Long> caseIds,
                         boolean reused) {
    }

    /** 重算结果：match=false 时 Controller 返回 409 DIGEST_MISMATCH，绝不建 run。 */
    public record Recompute(boolean match,
                            String expectedSha,
                            String recomputedSha,
                            CommandDigest.Diff diff,
                            List<Long> currentCaseIds) {
    }

    private final SnapshotStore store;
    private final SelectionResolver resolver;
    private final AccessControlService access;

    public SelectionSnapshotService(SnapshotStore store,
                                    SelectionResolver resolver,
                                    AccessControlService access) {
        this.store = store;
        this.resolver = resolver;
        this.access = access;
    }

    /** 确定性内容摘要（纯函数）。 */
    public static String computeSha(long projectId, String mode, List<Long> setIds,
                                    List<Long> caseIds, String canonicalFilter, int caseCount) {
        String material = String.join("|",
                Long.toString(projectId),
                mode == null ? "" : mode,
                idsJson(setIds),
                caseIds == null ? "" : idsJson(caseIds),
                canonicalFilter == null ? "" : canonicalFilter,
                Integer.toString(caseCount));
        return CommandDigest.sha256Hex(material);
    }

    /** 规范化筛选条件：key 升序、值转字符串，输出确定性 JSON。 */
    public static String canonicalFilter(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return "{}";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            sorted.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        try {
            return JSON.writeValueAsString(sorted);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("筛选条件序列化失败", e);
        }
    }

    public static Map<String, Object> parseFilter(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "快照筛选条件损坏，无法重算");
        }
    }

    /** id 数组 JSON：先升序排序，保证同输入同摘要。 */
    public static String idsJson(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        List<Long> sorted = new ArrayList<>();
        for (Long id : ids) {
            sorted.add(id == null ? 0L : id);
        }
        Collections.sort(sorted, Comparator.naturalOrder());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(sorted.get(i));
        }
        return sb.append("]").toString();
    }

    public static List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String body = json.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : body.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(token));
            } catch (NumberFormatException ignored) {
                // 脏数据跳过，不放大选择范围
            }
        }
        return List.copyOf(ids);
    }

    /** 冻结：解析选择 → 算摘要 → 命中未消费快照则复用，否则插入一行。 */
    public Frozen freeze(long projectId, SelectionResolver.SelectionMode mode,
                         List<Long> setIds, List<Long> caseIds, Map<String, Object> filter) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        if (mode == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "缺少 mode");
        }
        validateInput(mode, setIds, caseIds);
        SelectionResolver.Resolution resolution =
                resolver.resolve(projectId, mode, setIds, caseIds, filter);
        if (resolution.caseIds().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "选择为空（或全部用例不属于该项目），拒绝冻结快照");
        }
        String canonical = canonicalFilter(filter);
        String sha = computeSha(projectId, mode.name(), setIds, resolution.caseIds(),
                canonical, resolution.count());

        Optional<SnapshotRow> existing = store.findBySha(projectId, sha);
        if (existing.isPresent()) {
            if (existing.get().consumedAt() == null) {
                return new Frozen(existing.get().id(), sha, resolution.count(),
                        resolution.caseIds(), true);
            }
            // 迁移的唯一键是全局 sha：已消费的同输入既不能复用、也不能重复 INSERT。
            // 这里如实返回冲突，不伪造新 sha、不假装能重写历史快照。
            throw new BusinessException(ErrorCode.CONFLICT,
                    "相同选择的快照已被消费；uk_execution_selection_sha 不允许重写，请调整选择后重新预览");
        }
        long id = store.insert(new SnapshotRow(null, projectId, sha, mode.name(),
                idsJson(setIds), idsJson(resolution.caseIds()), canonical,
                resolution.count(), null, Instant.now()));
        return new Frozen(id, sha, resolution.count(), resolution.caseIds(), false);
    }

    /** 重算：用同一份输入重新解析当前用例库，与冻结摘要比对，并给出增删改序编号差异。 */
    public Recompute recomputeSha(long projectId, long snapshotId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        SnapshotRow row = store.findById(projectId, snapshotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "选择快照不存在：" + snapshotId));
        SelectionResolver.SelectionMode mode = SelectionResolver.parseMode(row.mode());
        List<Long> setIds = parseIds(row.setIdsJson());
        List<Long> frozenCaseIds = parseIds(row.caseIdsJson());
        Map<String, Object> filter = parseFilter(row.filterJson());
        SelectionResolver.Resolution now =
                resolver.resolve(projectId, mode, setIds, frozenCaseIds, filter);
        String recomputed = computeSha(projectId, mode.name(), setIds, now.caseIds(),
                canonicalFilter(filter), now.count());
        CommandDigest.Diff diff = CommandDigest.diffIds(frozenCaseIds, now.caseIds());
        return new Recompute(recomputed.equals(row.selectionSha()),
                row.selectionSha(), recomputed, diff, now.caseIds());
    }

    /** 消费：consumed_at 非空或并发抢先都拒绝复用，返回冻结清单供发起执行。 */
    public List<Long> consume(long projectId, long snapshotId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.CREATE);
        SnapshotRow row = store.findById(projectId, snapshotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "选择快照不存在：" + snapshotId));
        if (row.consumedAt() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "快照已消费，拒绝复用");
        }
        int updated = store.markConsumed(projectId, snapshotId, Instant.now(), TenantContext.userId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "快照已被并发消费，拒绝复用");
        }
        return parseIds(row.caseIdsJson());
    }

    private static void validateInput(SelectionResolver.SelectionMode mode,
                                      List<Long> setIds, List<Long> caseIds) {
        boolean noSets = setIds == null || setIds.isEmpty();
        boolean noCases = caseIds == null || caseIds.isEmpty();
        switch (mode) {
            case SET -> {
                if (noSets) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "SET 模式需要 setIds");
                }
            }
            case CASE -> {
                if (noCases) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "CASE 模式需要 caseIds");
                }
            }
            case MIXED -> {
                if (noSets && noCases) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                            "MIXED 模式至少需要 setIds 或 caseIds 之一");
                }
            }
            default -> {
                // ALL 无必填输入
            }
        }
    }
}
