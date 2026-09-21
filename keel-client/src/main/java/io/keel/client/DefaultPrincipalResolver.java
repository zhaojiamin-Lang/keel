package io.keel.client;

import java.util.List;
import java.util.Objects;

import io.keel.core.KeelPrincipal;

/**
 * 默认身份解析器：从配置固定取值（{@code keel.principal.*}）。
 *
 * <p>存在的意义是<b>保证零配置可启动</b>——与 NFR-04 那轮定下的取舍一致：
 * 可选能力缺失时不阻塞启动。多租户业务应注册自己的 {@link PrincipalResolver}
 * bean 覆盖本实现（starter 用 {@code @ConditionalOnMissingBean} 让位）。</p>
 *
 * <p>注意：本实现返回的身份对<b>所有请求相同</b>，因此只适合单租户 / 内网工具 / 示例场景。
 * 多租户下用它会导致跨租户数据串号（NFR-02 把租户隔离失败视为 P0 缺陷），
 * 生产请务必换成请求级实现。</p>
 */
public class DefaultPrincipalResolver implements PrincipalResolver {

    private final KeelPrincipal principal;

    public DefaultPrincipalResolver(String tenantId, String subjectId, List<String> resourceIds) {
        // KeelPrincipal 构造器已对 tenantId/subjectId 做非空校验，这里直接透传，
        // 让配置写错时在启动期就暴露（而不是每请求才报）
        Objects.requireNonNull(tenantId, "keel.principal.tenant-id 不能为空");
        Objects.requireNonNull(subjectId, "keel.principal.subject-id 不能为空");
        this.principal = new KeelPrincipal(tenantId, subjectId, resourceIds);
    }

    @Override
    public KeelPrincipal resolve() {
        return principal;
    }
}
