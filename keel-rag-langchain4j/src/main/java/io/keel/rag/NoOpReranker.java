package io.keel.rag;

import java.util.List;

import io.keel.core.Citation;

/**
 * FR-41：默认 Reranker——不做任何重排序，直接返回原列表。
 *
 * <p>用于不启用 rerank 时的占位，保持向后兼容。</p>
 */
public class NoOpReranker implements Reranker {

    @Override
    public List<Citation> rerank(String query, List<Citation> citations) {
        return citations;
    }
}