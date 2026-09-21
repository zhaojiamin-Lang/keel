package io.keel.memory;

/**
 * 记忆隔离的复合作用域：tenant + principal + resource（FR-53/FR-60）。
 * 所有 store 按此键隔离，禁止跨租户/跨主体/跨资源读写。
 *
 * <p>resourceId 为 "*" 时表示租户级共享记忆（如偏好语言），不绑定具体业务资源；
 * 业务资源相关记忆（如「上次查过工单 T-001」）必须带具体 resourceId。</p>
 */
public record TenantSubject(String tenantId, String subjectId, String resourceId) {

    /** 向后兼容：不指定 resourceId 时默认为 "*"（租户级共享）。 */
    public TenantSubject(String tenantId, String subjectId) {
        this(tenantId, subjectId, "*");
    }
}