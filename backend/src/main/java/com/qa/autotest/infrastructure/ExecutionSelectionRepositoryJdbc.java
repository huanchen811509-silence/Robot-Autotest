package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.ExecutionSelectionRepository;
import com.qa.autotest.domain.caseasset.FrozenSelection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 执行选择冻结快照仓储实现（SCEN-02）：写入只发 INSERT，查询只读 SELECT。 */
@Repository
public class ExecutionSelectionRepositoryJdbc implements ExecutionSelectionRepository {

    private static final RowMapper<FrozenSelection> MAPPER = (rs, rowNum) -> new FrozenSelection(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getObject("design_id") == null ? null : rs.getLong("design_id"),
            rs.getString("payload_json"),
            rs.getString("resource_snapshot_json"),
            rs.getString("sha256"),
            JdbcSupport.instant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public ExecutionSelectionRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(FrozenSelection selection) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO execution_selection
                      (project_id, design_id, payload_json, resource_snapshot_json, sha256)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, selection.projectId());
            ps.setObject(2, selection.designId(), java.sql.Types.BIGINT);
            ps.setString(3, selection.payloadJson());
            ps.setString(4, selection.resourceSnapshotJson());
            ps.setString(5, selection.sha256());
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
    }

    @Override
    public Optional<FrozenSelection> find(long projectId, long id) {
        String sql = "SELECT id, project_id, design_id, payload_json, resource_snapshot_json, "
                + "sha256, created_at FROM execution_selection WHERE project_id = ? AND id = ?";
        return JdbcSupport.first(jdbc.query(sql, MAPPER, projectId, id));
    }
}
