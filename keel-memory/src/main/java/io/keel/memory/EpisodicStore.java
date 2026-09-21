package io.keel.memory;

import java.util.List;

import io.keel.core.MemoryFragment;

/**
 * L2 情景记忆存储（FR-51）：会话 / 任务级轨迹与摘要，默认 Mongo。
 * key 必须带 tenant + principal + resource（FR-53/FR-60），实现自行保证隔离。
 */
public interface EpisodicStore {

    /** 追加一条情景记忆（按时间序）。 */
    void append(MemoryFragment fragment);

    /** 按作用域取最近 maxItems 条（最新的在前）。 */
    List<MemoryFragment> recent(String tenantId, String subjectId, int maxItems);

    /**
     * FR-60：按 resource 作用域取情景记忆。
     * resourceId 为 "*" 表示租户级共享；具体 resourceId 只取该资源的记忆。
     * 默认实现回退到无 resource 重载（向后兼容）。
     */
    default List<MemoryFragment> recent(
            String tenantId, String subjectId, String resourceId, int maxItems) {
        return recent(tenantId, subjectId, maxItems);
    }
}