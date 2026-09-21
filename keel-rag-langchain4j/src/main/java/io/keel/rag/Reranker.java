package io.keel.rag;

import java.util.List;

import io.keel.core.Citation;

/**
 * FR-41：Rerank端口——对初步召回的 {@link Citation} 列表进行二次排序，提升相关性。
 *
 * <p>默认实现 {@link NoOpReranker} 保持原序；生产可替换为交叉编码器或 LLM rerank。</p>
 */
public interface Reranker {

    /**
     * 对召回结果重新排序。
     *
     * @param query 原始查询
     * @param citations 初步召回结果（已过滤 ACL）
     * @return 重排序后的引用列表
     */
    List<Citation> rerank(String query, List<Citation> citations);
}