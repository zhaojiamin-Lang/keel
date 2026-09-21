package io.keel.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import io.keel.core.MemoryFragment;
import io.keel.core.MemoryLayer;

/**
 * 内存 L2 情景存储（示例 / 测试用，非生产）。
 * 按 tenant+principal 隔离（FR-53），每作用域最多保留 {@link #MAX_PER_SCOPE} 条，避免无界增长。
 */
public final class InMemoryEpisodicStore implements EpisodicStore {

    private static final int MAX_PER_SCOPE = 200;

    private final Map<TenantSubject, Deque<MemoryFragment>> store = new ConcurrentHashMap<>();

    @Override
    public void append(MemoryFragment fragment) {
        Objects.requireNonNull(fragment, "fragment");
        if (fragment.getLayer() != MemoryLayer.L2) {
            throw new IllegalArgumentException("L2 情景存储只接受 L2 片段: " + fragment.getLayer());
        }
        Deque<MemoryFragment> deque = store.computeIfAbsent(
                new TenantSubject(fragment.getTenantId(), fragment.getSubjectId()),
                key -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(fragment);
            while (deque.size() > MAX_PER_SCOPE) {
                deque.removeFirst();
            }
        }
    }

    @Override
    public List<MemoryFragment> recent(String tenantId, String subjectId, int maxItems) {
        Deque<MemoryFragment> deque = store.get(new TenantSubject(tenantId, subjectId));
        if (deque == null || deque.isEmpty() || maxItems <= 0) {
            return List.of();
        }
        List<MemoryFragment> snapshot = new ArrayList<>(deque);
        int from = Math.max(0, snapshot.size() - maxItems);
        List<MemoryFragment> result = snapshot.subList(from, snapshot.size());
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    /**
     * FR-60：按 resource 作用域取情景记忆。
     * 内存实现的 L2 情景记忆默认为租户级共享（resourceId="*"），不区分具体资源；
     * 传入具体 resourceId 时返回空（内存实现不存储 resource 级 L2 记忆）。
     */
    @Override
    public List<MemoryFragment> recent(
            String tenantId, String subjectId, String resourceId, int maxItems) {
        if ("*".equals(resourceId)) {
            return recent(tenantId, subjectId, maxItems);
        }
        return List.of();
    }
}
