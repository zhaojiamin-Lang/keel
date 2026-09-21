package io.keel.core;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

public final class KeelPrincipal implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String tenantId;
    private final String subjectId;
    private final List<String> resourceIds;

    public KeelPrincipal(String tenantId, String subjectId, List<String> resourceIds) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public List<String> getResourceIds() {
        return resourceIds;
    }
}
