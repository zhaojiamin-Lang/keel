package io.keel.memory;

import java.util.List;

import io.keel.core.MemoryFragment;

/**
 * L3 长期记忆存储（FR-52）：稳定偏好 / 规范摘要，PG 或向量，需可撤销。
 * key 必须带 tenant + principal + resource（FR-53/FR-60），实现自行保证隔离。
 */
public interface LongTermStore {

    /** 写入 / 覆盖一条长期记忆（以 {@code fragment.getKey()} 幂等）。 */
    void upsert(MemoryFragment fragment);

    /** 撤销一条长期记忆（FR-52 需可撤销）。 */
    void revoke(String tenantId, String subjectId, String key);

    /** 按作用域取最多 maxItems 条长期记忆（最新的在前）。 */
    List<MemoryFragment> search(String tenantId, String subjectId, int maxItems);

    /**
     * FR-60：按 resource 作用域取长期记忆。
     * resourceId 为 "*" 表示租户级共享；具体 resourceId 只取该资源的记忆。
     * 默认实现回退到无 resource 重载（向后兼容）。
     */
    default List<MemoryFragment> search(
            String tenantId, String subjectId, String resourceId, int maxItems) {
        return search(tenantId, subjectId, maxItems);
    }

    /**
     * FR-52（向量后端）：按语义相关性召回长期记忆。
     * query 为用户请求原文，实现可用 embedding 相似度排序；
     * 非向量实现退化为时间序召回（等价 {@link #search(String, String, int)}）。
     */
    default List<MemoryFragment> semanticSearch(
            String tenantId, String subjectId, String query, int maxItems) {
        return search(tenantId, subjectId, maxItems);
    }
}