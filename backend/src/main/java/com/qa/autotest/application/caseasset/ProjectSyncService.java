package com.qa.autotest.application.caseasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 存量项目同步（v4.9.0 / FR-v4.9-01..07）。
 *
 * <p>三态语义：`NEW` 公共库新增、`UPDATE` 本地未改可直接覆盖、`CONFLICT` 本地有改动需人工选择。
 * 预览零写入（只写 sync_job/sync_job_item）；执行前把本地快照写入 `before_json`，回滚据此还原。
 * 标记 `local_override` 的本地用例永不被同步覆盖。
 */
@Service
public class ProjectSyncService {

    public record SyncItemRow(long id, String externalId, long remoteId, Long localId, String action,
                              String localSha, String remoteSha, String resolution, String message,
                              String title, String categoryL1, String priority) {
    }

    public record SyncPreview(long jobId, int total, int newCount, int updateCount,
                              int conflictCount, int unchangedCount, int skippedCount,
                              String previewSha, List<SyncItemRow> items) {
    }

    public record SyncExecResult(long jobId, int created, int updated, int conflictsKept,
                                 int duplicated, boolean replayed) {
    }

    public record SyncRollbackResult(long jobId, int removed, int restored) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformProjectService platform;
    private final AccessControlService access;
    private final AuditService audit;

    public ProjectSyncService(JdbcTemplate jdbc, ObjectMapper mapper,
                              PlatformProjectService platform, AccessControlService access,
                              AuditService audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.platform = platform;
        this.access = access;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 预览

    @Transactional
    public SyncPreview preview(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        long projectIdChecked = projectId;
        long platformId = platform.id();
        Map<String, Map<String, Object>> local = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, external_id, content_sha, source, lifecycle_status, updated_at"
                        + " FROM test_case WHERE project_id = ? AND external_id IS NOT NULL",
                projectIdChecked)) {
            local.put(key(String.valueOf(row.get("external_id"))), row);
        }
        List<Map<String, Object>> remote = jdbc.queryForList("""
                SELECT id, external_id, content_sha, title, category_l1, category_l2, priority,
                       lifecycle_status
                  FROM test_case WHERE project_id = ? AND external_id IS NOT NULL
                """, platformId);

        long operator = TenantContext.requireUserId();
        KeyHolder jobKeys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO sync_job (project_id, source_kind, scope, mode, status, created_by)
                    VALUES (?, 'GLOBAL', 'CASES', 'track-latest', 'PREVIEW', ?)
                    """, new String[]{"id"});
            ps.setLong(1, projectIdChecked);
            ps.setLong(2, operator);
            return ps;
        }, jobKeys);
        long jobId = jobKeys.getKey() == null ? 0 : jobKeys.getKey().longValue();

        int newCount = 0;
        int updateCount = 0;
        int conflictCount = 0;
        int unchangedCount = 0;
        int skippedCount = 0;
        int localOnlyCount = 0;
        List<SyncItemRow> items = new ArrayList<>();
        StringBuilder shaSource = new StringBuilder();
        java.util.Set<String> remoteKeys = new java.util.HashSet<>();

        for (Map<String, Object> remoteRow : remote) {
            String externalId = String.valueOf(remoteRow.get("external_id"));
            String remoteSha = str(remoteRow.get("content_sha"));
            Map<String, Object> localRow = local.get(key(externalId));
            String action;
            String message = "";
            Long localId = null;
            String localSha = null;
            if (localRow == null) {
                action = "NEW";
                message = "公共库新增用例";
                newCount++;
            } else {
                localId = ((Number) localRow.get("id")).longValue();
                localSha = str(localRow.get("content_sha"));
                if ("local_override".equals(str(localRow.get("source")))) {
                    action = "SKIP";
                    message = "本地覆盖（local_override），不同步";
                    skippedCount++;
                } else if ("deprecated".equals(str(localRow.get("lifecycle_status")))) {
                    action = "UPDATE";
                    message = "本地已失效，按上游恢复";
                    updateCount++;
                } else if (remoteSha != null && remoteSha.equals(localSha)) {
                    action = "UNCHANGED";
                    unchangedCount++;
                } else if (localSha == null) {
                    action = "UPDATE";
                    message = "本地内容指纹缺失，按上游补齐";
                    updateCount++;
                } else {
                    action = "CONFLICT";
                    message = "本地内容与公共库不一致，需选择保留或接受上游";
                    conflictCount++;
                }
            }
            jdbc.update("""
                    INSERT INTO sync_job_item (job_id, item_type, external_id, local_id, remote_id,
                        action, local_sha, remote_sha, message)
                    VALUES (?, 'CASE', ?, ?, ?, ?, ?, ?, ?)
                    """, jobId, externalId, localId, remoteRow.get("id"), action, localSha,
                    remoteSha, message);
            shaSource.append(externalId).append(':').append(remoteSha).append(';');
            remoteKeys.add(key(externalId));
            items.add(new SyncItemRow(jobId, externalId,
                    ((Number) remoteRow.get("id")).longValue(), localId, action, localSha, remoteSha,
                    null, message, str(remoteRow.get("title")), str(remoteRow.get("category_l1")),
                    str(remoteRow.get("priority"))));
        }

        // 本地有、公共库没有：保留本地（SKIP），不删除项目用例。
        for (Map.Entry<String, Map<String, Object>> entry : local.entrySet()) {
            if (!remoteKeys.contains(entry.getKey())) {
                Map<String, Object> localRow = entry.getValue();
                jdbc.update("""
                        INSERT INTO sync_job_item (job_id, item_type, external_id, local_id, action,
                                                   message, local_sha)
                        VALUES (?, 'CASE', ?, ?, 'SKIP', '公共库已无此用例，保留本地', ?)
                        """, jobId, localRow.get("external_id"), localRow.get("id"),
                        localRow.get("content_sha"));
                skippedCount++;
                localOnlyCount++;
            }
        }

        String previewSha = sha256(shaSource.toString());
        jdbc.update("""
                UPDATE sync_job SET total = ?, new_count = ?, updated_count = ?, conflict_count = ?,
                       unchanged_count = ?, skipped_count = ?, preview_sha = ?
                 WHERE id = ?
                """, items.size() + localOnlyCount,
                newCount, updateCount, conflictCount, unchangedCount, skippedCount, previewSha, jobId);
        audit.record("project.sync.preview", "sync_job", String.valueOf(jobId),
                "project=" + projectIdChecked + " new=" + newCount + " update=" + updateCount
                        + " conflict=" + conflictCount);
        return new SyncPreview(jobId, items.size() + localOnlyCount, newCount, updateCount,
                conflictCount, unchangedCount, skippedCount, previewSha, items);
    }

    // ---------------------------------------------------------------- 执行

    @Transactional
    public SyncExecResult execute(long jobId, Map<Long, String> resolutions) {
        Map<String, Object> job = loadJob(jobId);
        long projectId = ((Number) job.get("project_id")).longValue();
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        if ("DONE".equals(job.get("status"))) {
            return new SyncExecResult(jobId, 0, 0, 0, 0, true);
        }
        if (!"PREVIEW".equals(job.get("status"))) {
            throw new BusinessException(ErrorCode.CONFLICT, "该同步作业已执行或已回滚，不能重复执行");
        }
        long operator = TenantContext.requireUserId();
        int created = 0;
        int updated = 0;
        int conflictsKept = 0;
        int duplicated = 0;
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM sync_job_item WHERE job_id = ? ORDER BY id", jobId);
        for (Map<String, Object> item : items) {
            String action = String.valueOf(item.get("action"));
            long itemId = ((Number) item.get("id")).longValue();
            long remoteId = ((Number) item.get("remote_id")).longValue();
            Long localId = item.get("local_id") == null ? null
                    : ((Number) item.get("local_id")).longValue();
            String resolution = resolutions == null ? null : resolutions.get(itemId);
            switch (action) {
                case "NEW" -> {
                    long newId = copyCase(projectId, remoteId, operator, null);
                    jdbc.update("UPDATE sync_job_item SET local_id = ? WHERE id = ?", newId, itemId);
                    created++;
                }
                case "UPDATE" -> {
                    if (localId == null) {
                        long newId = copyCase(projectId, remoteId, operator, null);
                        jdbc.update("UPDATE sync_job_item SET local_id = ? WHERE id = ?", newId, itemId);
                        created++;
                    } else {
                        snapshotBefore(itemId, localId);
                        overwriteFromRemote(projectId, localId, remoteId, operator);
                        jdbc.update("UPDATE sync_job_item SET resolution = 'TAKE_REMOTE' WHERE id = ?",
                                itemId);
                        updated++;
                    }
                }
                case "CONFLICT" -> {
                    if ("TAKE_REMOTE".equals(resolution) && localId != null) {
                        snapshotBefore(itemId, localId);
                        overwriteFromRemote(projectId, localId, remoteId, operator);
                        jdbc.update("UPDATE sync_job_item SET resolution = 'TAKE_REMOTE' WHERE id = ?",
                                itemId);
                        updated++;
                    } else if ("DUPLICATE".equals(resolution)) {
                        long newId = copyCase(projectId, remoteId, operator, "-SYNC");
                        jdbc.update("""
                                UPDATE sync_job_item SET resolution = 'DUPLICATE', local_id = ?
                                 WHERE id = ?
                                """, newId, itemId);
                        duplicated++;
                    } else {
                        jdbc.update("UPDATE sync_job_item SET resolution = 'KEEP_LOCAL' WHERE id = ?",
                                itemId);
                        conflictsKept++;
                    }
                }
                default -> {
                    // UNCHANGED / SKIP 不写
                }
            }
        }
        jdbc.update("""
                UPDATE sync_job SET status = 'DONE', executed_at = NOW(3), executed_by = ?,
                       new_count = ?, updated_count = ?, skipped_count = ?
                 WHERE id = ?
                """, operator, created, updated, conflictsKept + duplicated, jobId);
        audit.record("project.sync.execute", "sync_job", String.valueOf(jobId),
                "created=" + created + " updated=" + updated + " kept=" + conflictsKept
                        + " duplicated=" + duplicated);
        return new SyncExecResult(jobId, created, updated, conflictsKept, duplicated, false);
    }

    // ---------------------------------------------------------------- 回滚

    @Transactional
    public SyncRollbackResult rollback(long jobId) {
        Map<String, Object> job = loadJob(jobId);
        long projectId = ((Number) job.get("project_id")).longValue();
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        if (!"DONE".equals(job.get("status"))) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有已执行的同步作业可以回滚");
        }
        int removed = 0;
        int restored = 0;
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM sync_job_item WHERE job_id = ? ORDER BY id DESC", jobId);
        for (Map<String, Object> item : items) {
            String action = String.valueOf(item.get("action"));
            Long localId = item.get("local_id") == null ? null
                    : ((Number) item.get("local_id")).longValue();
            String before = item.get("before_json") == null ? null
                    : String.valueOf(item.get("before_json"));
            if (localId == null) {
                continue;
            }
            if ("NEW".equals(action) || "DUPLICATE".equals(str(item.get("resolution")))) {
                jdbc.update("DELETE FROM test_case_step WHERE test_case_id = ?", localId);
                jdbc.update("DELETE FROM test_case WHERE id = ? AND project_id = ?", localId, projectId);
                removed++;
            } else if (before != null) {
                restoreCase(projectId, localId, before);
                restored++;
            } else if ("TAKE_REMOTE".equals(str(item.get("resolution")))) {
                // 没有快照的更新无法精确还原，保留但不谎报成功。
                continue;
            }
        }
        jdbc.update("UPDATE sync_job SET status = 'ROLLED_BACK', rolled_back_at = NOW(3) WHERE id = ?",
                jobId);
        audit.record("project.sync.rollback", "sync_job", String.valueOf(jobId),
                "removed=" + removed + " restored=" + restored);
        return new SyncRollbackResult(jobId, removed, restored);
    }

    // ------------------------------------------------------------ 来源切换

    public void markLocalOverride(long projectId, long caseId) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        jdbc.update("""
                UPDATE test_case SET source = 'local_override', updated_by = ?
                 WHERE id = ? AND project_id = ?
                """, TenantContext.requireUserId(), caseId, projectId);
    }

    public List<Map<String, Object>> jobs(long projectId, int limit) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        return jdbc.queryForList("""
                SELECT id, status, total, new_count, updated_count, conflict_count, unchanged_count,
                       skipped_count, preview_sha, created_at, executed_at, rolled_back_at
                  FROM sync_job WHERE project_id = ? ORDER BY id DESC LIMIT ?
                """, projectId, Math.min(Math.max(limit, 1), 50));
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> loadJob(long jobId) {
        List<Map<String, Object>> jobs = jdbc.queryForList(
                "SELECT * FROM sync_job WHERE id = ?", jobId);
        if (jobs.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "同步作业不存在");
        }
        return jobs.get(0);
    }

    private void snapshotBefore(long itemId, long localId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM test_case WHERE id = ?", localId);
            if (rows.isEmpty()) {
                return;
            }
            String json = mapper.writeValueAsString(rows.get(0));
            jdbc.update("UPDATE sync_job_item SET before_json = ? WHERE id = ?", json, itemId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "同步快照序列化失败");
        }
    }

    private void overwriteFromRemote(long projectId, long localId, long remoteId, long operator) {
        Map<String, Object> remote = jdbc.queryForMap(
                "SELECT * FROM test_case WHERE id = ?", remoteId);
        jdbc.update("""
                UPDATE test_case SET title = ?, case_kind = ?, priority = ?, safety_level = ?,
                       domain_tag = ?, description = ?, precondition = ?, expected = ?, capability = ?,
                       category_l1 = ?, category_l2 = ?, source_priority = ?,
                       case_class = ?, test_category = ?, ed202_coverage = ?, source_sheet = ?,
                       content_sha = ?, lifecycle_status = 'active', updated_by = ?
                 WHERE id = ? AND project_id = ?
                """, str(remote.get("title")), str(remote.get("case_kind")), str(remote.get("priority")),
                safety(remote.get("safety_level")), str(remote.get("domain_tag")),
                str(remote.get("description")), str(remote.get("precondition")),
                str(remote.get("expected")), str(remote.get("capability")),
                str(remote.get("category_l1")), str(remote.get("category_l2")),
                str(remote.get("source_priority")), str(remote.get("case_class")),
                str(remote.get("test_category")), str(remote.get("ed202_coverage")),
                str(remote.get("source_sheet")), str(remote.get("content_sha")), operator,
                localId, projectId);
        jdbc.update("DELETE FROM test_case_step WHERE test_case_id = ?", localId);
        jdbc.update("""
                INSERT INTO test_case_step (test_case_id, step_no, action, expectation, source_row,
                                            created_by, updated_by)
                SELECT ?, step_no, action, expectation, source_row, ?, ?
                  FROM test_case_step WHERE test_case_id = ?
                """, localId, operator, operator, remoteId);
    }

    private void restoreCase(long projectId, long localId, String beforeJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> before = mapper.readValue(beforeJson, Map.class);
            jdbc.update("""
                    UPDATE test_case SET title = ?, case_kind = ?, priority = ?, safety_level = ?,
                           domain_tag = ?, description = ?, precondition = ?, expected = ?, capability = ?,
                           category_l1 = ?, category_l2 = ?, source_priority = ?,
                           case_class = ?, test_category = ?, ed202_coverage = ?, source_sheet = ?,
                           content_sha = ?, lifecycle_status = COALESCE(?, 'active'),
                           source = COALESCE(?, 'import')
                     WHERE id = ? AND project_id = ?
                    """, before.get("title"), before.get("case_kind"), before.get("priority"),
                    safety(before.get("safety_level")), before.get("domain_tag"),
                    before.get("description"), before.get("precondition"), before.get("expected"),
                    before.get("capability"), before.get("category_l1"), before.get("category_l2"),
                    before.get("source_priority"), before.get("case_class"),
                    before.get("test_category"), before.get("ed202_coverage"),
                    before.get("source_sheet"), before.get("content_sha"),
                    before.get("lifecycle_status"), before.get("source"), localId, projectId);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "同步回滚失败：" + ex.getMessage());
        }
    }

    private long copyCase(long projectId, long remoteId, long operator, String codeSuffix) {
        Map<String, Object> remote = jdbc.queryForMap(
                "SELECT * FROM test_case WHERE id = ?", remoteId);
        String rawCode = str(remote.get("case_code"));
        if (rawCode == null) {
            rawCode = str(remote.get("external_id"));
        }
        final String code = codeSuffix == null ? rawCode : rawCode + codeSuffix;
        List<Long> exists = jdbc.queryForList(
                "SELECT id FROM test_case WHERE project_id = ? AND case_code = ?",
                Long.class, projectId, code);
        if (!exists.isEmpty()) {
            return exists.get(0);
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO test_case (project_id, case_code, external_id, title, case_kind,
                        source_type, priority, safety_level, domain_tag, status, description,
                        precondition, expected, review_status, source, capability,
                        category_l1, category_l2, source_priority, case_class, test_category,
                        ed202_coverage, source_sheet, content_sha, lifecycle_status, version_no,
                        created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'draft', 'import', ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, 'active', 1, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, projectId);
            ps.setString(2, truncate(code, 64));
            ps.setString(3, str(remote.get("external_id")));
            ps.setString(4, str(remote.get("title")));
            ps.setString(5, str(remote.get("case_kind")));
            ps.setString(6, remote.get("source_type") == null ? "ledger"
                    : str(remote.get("source_type")));
            ps.setString(7, str(remote.get("priority")));
            ps.setString(8, safety(remote.get("safety_level")));
            ps.setString(9, str(remote.get("domain_tag")));
            ps.setObject(10, remote.get("status") == null ? 1 : remote.get("status"));
            ps.setString(11, str(remote.get("description")));
            ps.setString(12, str(remote.get("precondition")));
            ps.setString(13, str(remote.get("expected")));
            ps.setString(14, str(remote.get("capability")));
            ps.setString(15, str(remote.get("category_l1")));
            ps.setString(16, str(remote.get("category_l2")));
            ps.setString(17, str(remote.get("source_priority")));
            ps.setString(18, str(remote.get("case_class")));
            ps.setString(19, str(remote.get("test_category")));
            ps.setString(20, str(remote.get("ed202_coverage")));
            ps.setString(21, str(remote.get("source_sheet")));
            ps.setString(22, str(remote.get("content_sha")));
            ps.setLong(23, operator);
            ps.setLong(24, operator);
            return ps;
        }, keys);
        long newId = keys.getKey() == null ? 0 : keys.getKey().longValue();
        jdbc.update("""
                INSERT INTO test_case_step (test_case_id, step_no, action, expectation, source_row,
                                            created_by, updated_by)
                SELECT ?, step_no, action, expectation, source_row, ?, ?
                  FROM test_case_step WHERE test_case_id = ?
                """, newId, operator, operator, remoteId);
        return newId;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /** safety_level 为 NOT NULL DEFAULT 'LX'，不能显式写 NULL。 */
    private static String safety(Object value) {
        String text = str(value);
        return text == null || text.isBlank() ? "LX" : text;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 便于单测与页面展示：动作集合。 */
    public static Set<String> actions() {
        return Set.of("NEW", "UPDATE", "CONFLICT", "UNCHANGED", "SKIP");
    }
}
