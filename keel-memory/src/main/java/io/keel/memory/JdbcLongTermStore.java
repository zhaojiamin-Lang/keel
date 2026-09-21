package io.keel.memory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.keel.core.MemoryFragment;
import io.keel.core.MemoryLayer;

/**
 * 基于 PostgreSQL 的 L3 长期记忆存储（FR-52）。
 *
 * <p>稳定偏好 / 规范摘要落在 PG，支持幂等 upsert 与可撤销（revoke）。key 带
 * tenant + principal + key 三级隔离（FR-53）。写路径建议由业务保证幂等表 / Outbox
 * 一致性（NFR-03），本类只提供原子 upsert。</p>
 */
public final class JdbcLongTermStore implements LongTermStore {

    private static final String TABLE = "keel_long_term_memory";

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public JdbcLongTermStore(String jdbcUrl, String user, String password) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("PG L3 长期存储必须配置 jdbcUrl");
        }
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    @Override
    public void upsert(MemoryFragment fragment) {
        Objects.requireNonNull(fragment, "fragment");
        if (fragment.getLayer() != MemoryLayer.L3) {
            throw new IllegalArgumentException("L3 长期存储只接受 L3 片段: " + fragment.getLayer());
        }
        if (fragment.getKey() == null || fragment.getKey().isBlank()) {
            throw new IllegalArgumentException("L3 长期记忆必须携带非空 key（用于幂等与撤销）");
        }
        String sql = "INSERT INTO " + TABLE
                + " (tenant_id, subject_id, mem_key, content, created_at) VALUES (?,?,?,?,?)"
                + " ON CONFLICT (tenant_id, subject_id, mem_key)"
                + " DO UPDATE SET content = EXCLUDED.content, created_at = EXCLUDED.created_at";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fragment.getTenantId());
            statement.setString(2, fragment.getSubjectId());
            statement.setString(3, fragment.getKey());
            statement.setString(4, fragment.getContent());
            statement.setObject(5, fragment.getCreatedAt());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("L3 长期记忆 upsert 失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void revoke(String tenantId, String subjectId, String key) {
        String sql = "DELETE FROM " + TABLE + " WHERE tenant_id=? AND subject_id=? AND mem_key=?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, subjectId);
            statement.setString(3, key);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("L3 长期记忆 revoke 失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public List<MemoryFragment> search(String tenantId, String subjectId, int maxItems) {
        String sql = "SELECT mem_key, content, created_at FROM " + TABLE
                + " WHERE tenant_id=? AND subject_id=? ORDER BY created_at DESC LIMIT ?";
        List<MemoryFragment> result = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, subjectId);
            statement.setInt(3, Math.max(0, maxItems));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new MemoryFragment(
                            MemoryLayer.L3,
                            tenantId,
                            subjectId,
                            rs.getString("mem_key"),
                            rs.getString("content"),
                            rs.getTimestamp("created_at").toInstant()));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("L3 长期记忆 search 失败: " + exception.getMessage(), exception);
        }
        return result;
    }
}
