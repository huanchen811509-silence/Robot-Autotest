package com.qa.autotest.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.repository.SavedViewRepository;
import com.qa.autotest.application.view.SavedView;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 保存视图 JDBC 实现。 */
@Repository
public class SavedViewRepositoryJdbc implements SavedViewRepository {

    private static final String COLUMNS =
            "id, project_id, user_id, name, view_scope, payload, is_default, created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SavedViewRepositoryJdbc(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<SavedView> list(long projectId, long userId, String scope) {
        String sql = "SELECT " + COLUMNS + " FROM saved_view "
                + "WHERE project_id = ? AND user_id = ? AND view_scope = ? "
                + "ORDER BY is_default DESC, updated_at DESC, id DESC";
        TenantGuard.requireProjectScope(sql, "saved_view");
        return jdbc.query(sql, (rs, rowNum) -> map(rs), projectId, userId, scope);
    }

    @Override
    public Optional<SavedView> find(long projectId, long id, long userId) {
        String sql = "SELECT " + COLUMNS + " FROM saved_view "
                + "WHERE project_id = ? AND id = ? AND user_id = ?";
        TenantGuard.requireProjectScope(sql, "saved_view");
        return jdbc.query(sql, (rs, rowNum) -> map(rs), projectId, id, userId).stream().findFirst();
    }

    @Override
    public long insert(SavedView view) {
        String sql = "INSERT INTO saved_view "
                + "(project_id, user_id, name, view_scope, payload, is_default, created_by, updated_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        TenantGuard.requireProjectScope(sql, "saved_view");
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, view.projectId());
            ps.setLong(2, view.userId());
            ps.setString(3, view.name());
            ps.setString(4, view.scope());
            ps.setString(5, payload(view.payload()));
            ps.setInt(6, view.isDefault() ? 1 : 0);
            ps.setLong(7, view.userId());
            ps.setLong(8, view.userId());
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    @Override
    public void update(SavedView view) {
        String sql = "UPDATE saved_view SET name = ?, view_scope = ?, payload = ?, "
                + "is_default = ?, updated_by = ? "
                + "WHERE project_id = ? AND id = ? AND user_id = ?";
        TenantGuard.requireProjectScope(sql, "saved_view");
        jdbc.update(sql, view.name(), view.scope(), payload(view.payload()),
                view.isDefault() ? 1 : 0, view.userId(), view.projectId(), view.id(), view.userId());
    }

    @Override
    public void delete(long projectId, long id, long userId) {
        String sql = "DELETE FROM saved_view WHERE project_id = ? AND id = ? AND user_id = ?";
        TenantGuard.requireProjectScope(sql, "saved_view");
        jdbc.update(sql, projectId, id, userId);
    }

    @Override
    public boolean nameExists(long projectId, long userId, String scope, String name, Long excludeId) {
        String sql = "SELECT COUNT(*) FROM saved_view "
                + "WHERE project_id = ? AND user_id = ? AND view_scope = ? AND name = ?"
                + (excludeId == null ? "" : " AND id <> ?");
        TenantGuard.requireProjectScope(sql, "saved_view");
        Long count = excludeId == null
                ? jdbc.queryForObject(sql, Long.class, projectId, userId, scope, name)
                : jdbc.queryForObject(sql, Long.class, projectId, userId, scope, name, excludeId);
        return count != null && count > 0;
    }

    @Override
    public void clearDefault(long projectId, long userId, String scope, long keepId) {
        String sql = "UPDATE saved_view SET is_default = 0 "
                + "WHERE project_id = ? AND user_id = ? AND view_scope = ? AND id <> ? AND is_default = 1";
        TenantGuard.requireProjectScope(sql, "saved_view");
        jdbc.update(sql, projectId, userId, scope, keepId);
    }

    private SavedView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SavedView(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getLong("user_id"),
                rs.getString("name"),
                rs.getString("view_scope"),
                readPayload(rs.getString("payload")),
                rs.getInt("is_default") == 1,
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private Object readPayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, Object.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存视图数据损坏");
        }
    }

    private String payload(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "payload 必须是合法 JSON");
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
