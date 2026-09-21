package io.keel.core;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 一条记忆片段（L2 情景 / L3 长期）。三层 key 必须带 tenant + principal（FR-53），
 * 因此本类强制携带 {@code tenantId} + {@code subjectId}，store 按二者隔离，
 * 禁止跨层混写同一个 blob。
 */
public final class MemoryFragment implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final MemoryLayer layer;
    private final String tenantId;
    private final String subjectId;
    /** L3 稳定记忆的可撤销键（如 preference:language）；L2 通常为 null */
    private final String key;
    private final String content;
    private final Instant createdAt;

    public MemoryFragment(MemoryLayer layer, String tenantId, String subjectId,
            String key, String content, Instant createdAt) {
        this.layer = Objects.requireNonNull(layer, "layer");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.key = key;
        this.content = Objects.requireNonNull(content, "content");
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public MemoryLayer getLayer() {
        return layer;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public String getKey() {
        return key;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
