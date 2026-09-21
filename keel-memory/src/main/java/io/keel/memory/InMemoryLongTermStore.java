package io.keel.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import io.keel.core.MemoryFragment;
import io.keel.core.MemoryLayer;

/**
 * 内存 L3 长期存储（示例 / 测试用，非生产）。
 * 按 tenant+principal+key 隔离（FR-53），支持幂等 upsert 与可撤销（FR-52）。
 */
public final class InMemoryLongTermStore implements LongTermStore {

    private final Map<TenantSubject, Map<String, MemoryFragment>> store = new ConcurrentHashMap<>();

    @Override
    public void upsert(MemoryFragment fragment) {
        Objects.requireNonNull(fragment, "fragment");
        if (fragment.getLayer() != MemoryLayer.L3) {
            throw new IllegalArgumentException("L3 长期存储只接受 L3 片段: " + fragment.getLayer());
        }
        if (fragment.getKey() == null || fragment.getKey().isBlank()) {
            throw new IllegalArgumentException("L3 长期记忆必须携带非空 key（用于幂等与撤销）");
        }
        // FR-60：L3 长期记忆默认为租户级共享（resourceId="*"），不绑定具体业务资源
        store.computeIfAbsent(
                new TenantSubject(fragment.getTenantId(), fragment.getSubjectId()),
                key -> new ConcurrentHashMap<>())
                .put(fragment.getKey(), fragment);
    }

    @Override
    public void revoke(String tenantId, String subjectId, String key) {
        Map<String, MemoryFragment> scope = store.get(new TenantSubject(tenantId, subjectId));
        if (scope != null) {
            scope.remove(key);
        }
    }

    @Override
    public List<MemoryFragment> search(String tenantId, String subjectId, int maxItems) {
        Map<String, MemoryFragment> scope = store.get(new TenantSubject(tenantId, subjectId));
        if (scope == null || scope.isEmpty()) {
            return List.of();
        }
        return scope.values().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(Math.max(0, maxItems))
                .toList();
    }

    /**
     * FR-60：按 resource 作用域取长期记忆。
     * 内存实现的 L3 长期记忆默认为租户级共享（resourceId="*"），不区分具体资源；
     * 传入具体 resourceId 时返回空（内存实现不存储 resource 级 L3 记忆）。
     */
    @Override
    public List<MemoryFragment> search(
            String tenantId, String subjectId, String resourceId, int maxItems) {
        if ("*".equals(resourceId)) {
            return search(tenantId, subjectId, maxItems);
        }
        // 内存实现不存储 resource 级 L3 记忆，具体 resourceId 返回空
        return List.of();
    }
}
