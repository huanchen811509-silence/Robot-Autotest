package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 用例 ↔ 脚本绑定仓储实现（V11_47 / FR-v5.1-04）。 */
@Repository
public class CaseScriptBindingRepositoryJdbc implements CaseScriptBindingRepository {

    private static final String SELECT = """
            SELECT id, project_id, case_id, kernel, script_path, node_id, script_sha256,
                   resolve_source, verified
              FROM case_script_binding
            """;

    private static final RowMapper<CaseScriptBinding> MAPPER = (rs, rowNum) -> new CaseScriptBinding(
            rs.getLong("id"), rs.getLong("project_id"), rs.getLong("case_id"),
            rs.getString("kernel"), rs.getString("script_path"), rs.getString("node_id"),
            rs.getString("script_sha256"), rs.getString("resolve_source"),
            rs.getBoolean("verified"));

    private final JdbcTemplate jdbc;

    public CaseScriptBindingRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CaseScriptBinding> findByCase(long projectId, long caseId, String kernel) {
        return jdbc.query(SELECT + " WHERE project_id = ? AND case_id = ? AND kernel = ?",
                MAPPER, projectId, caseId, kernel).stream().findFirst();
    }

    @Override
    public Optional<CaseScriptBinding> findByNodeId(long projectId, String kernel, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(SELECT + " WHERE project_id = ? AND kernel = ? AND node_id = ?",
                MAPPER, projectId, kernel, nodeId).stream().findFirst();
    }

    @Override
    public List<CaseScriptBinding> listByProject(long projectId, String kernel) {
        return jdbc.query(SELECT + " WHERE project_id = ? AND kernel = ? ORDER BY script_path, id",
                MAPPER, projectId, kernel);
    }

    @Override
    public Optional<CaseScriptBinding> findByPath(long projectId, String kernel, String scriptPath) {
        return jdbc.query(SELECT + " WHERE project_id = ? AND kernel = ? AND script_path = ?",
                MAPPER, projectId, kernel, scriptPath).stream().findFirst();
    }

    @Override
    public List<CaseScriptBinding> findBySha(long projectId, String kernel, String scriptSha256) {
        if (scriptSha256 == null || scriptSha256.isBlank()) {
            return List.of();
        }
        return jdbc.query(SELECT + " WHERE project_id = ? AND kernel = ? AND script_sha256 = ?",
                MAPPER, projectId, kernel, scriptSha256);
    }

    @Override
    public int deleteByProjectAndKernel(long projectId, String kernel) {
        return jdbc.update("DELETE FROM case_script_binding WHERE project_id = ? AND kernel = ?",
                projectId, kernel);
    }

    @Override
    public long insert(CaseScriptBinding binding, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO case_script_binding
                      (project_id, case_id, kernel, script_path, node_id, script_sha256,
                       resolve_source, verified, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, binding.projectId());
            ps.setLong(2, binding.caseId());
            ps.setString(3, binding.kernel());
            ps.setString(4, binding.scriptPath());
            ps.setString(5, binding.nodeId());
            ps.setString(6, binding.scriptSha256());
            ps.setString(7, binding.resolveSource());
            ps.setBoolean(8, binding.verified());
            if (operatorId == null) {
                ps.setNull(9, java.sql.Types.BIGINT);
                ps.setNull(10, java.sql.Types.BIGINT);
            } else {
                ps.setLong(9, operatorId);
                ps.setLong(10, operatorId);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    @Override
    public void update(CaseScriptBinding binding, Long operatorId) {
        jdbc.update("""
                UPDATE case_script_binding
                   SET case_id = ?, script_path = ?, node_id = ?, script_sha256 = ?,
                       resolve_source = ?, verified = ?, updated_by = ?
                 WHERE id = ? AND project_id = ? AND kernel = ?
                """, binding.caseId(), binding.scriptPath(), binding.nodeId(),
                binding.scriptSha256(), binding.resolveSource(), binding.verified(),
                operatorId, binding.id(), binding.projectId(), binding.kernel());
    }

    @Override
    public void deleteById(long projectId, long id) {
        jdbc.update("DELETE FROM case_script_binding WHERE id = ? AND project_id = ?", id, projectId);
    }

    @Override
    public long countByProject(long projectId, String kernel) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_script_binding WHERE project_id = ? AND kernel = ?",
                Long.class, projectId, kernel);
        return count == null ? 0L : count;
    }
}
