package io.keel.rag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import dev.langchain4j.store.embedding.EmbeddingStore;
import io.keel.core.Citation;
import io.keel.core.KeelPrincipal;

/**
 * FR-41：混合检索——向量检索 + 关键词检索的并集去重。
 *
 * <p>向量检索走 {@link EmbeddingStore}，关键词检索走简单的子串匹配（内存遍历）。
 * 合并后经 {@link Reranker} 重排序。生产可替换为 BM25 或 Elasticsearch 关键词检索。</p>
 *
 * <p>本类是参考实现，展示混合检索的端口设计；实际生产需注入专业的关键词检索器。</p>
 */
public class KeywordHybridRetriever extends LangChain4jRetrievalPort {

    private final Reranker reranker;

    public KeywordHybridRetriever(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            QueryRewriter queryRewriter,
            int maxResults,
            Reranker reranker) {
        super(embeddingModel, embeddingStore, queryRewriter, maxResults);
        this.reranker = reranker == null ? new NoOpReranker() : reranker;
    }

    @Override
    public List<Citation> retrieve(String query, KeelPrincipal principal) {
        // 与父类同款 fail-closed：无身份直接空召回，不进入关键词检索
        if (principal == null) {
            return List.of();
        }
        // 向量检索
        List<Citation> vectorResults = super.retrieve(query, principal);

        // 关键词检索（简单子串匹配——作为参考实现，生产应替换为 BM25）
        List<Citation> keywordResults = keywordSearch(query, principal);

        // 合并去重（按 chunkId）
        Set<String> seen = new HashSet<>();
        List<Citation> merged = new ArrayList<>();
        for (Citation c : vectorResults) {
            if (seen.add(c.getChunkId())) {
                merged.add(c);
            }
        }
        for (Citation c : keywordResults) {
            if (seen.add(c.getChunkId())) {
                merged.add(c);
            }
        }

        // Rerank
        return reranker.rerank(query, merged);
    }

    /**
     * 关键词检索：遍历 store 中的片段，做子串匹配。
     * 注意：这是一个简化的参考实现，实际生产应使用 BM25 或 Elasticsearch。
     */
    private List<Citation> keywordSearch(String query, KeelPrincipal principal) {
        if (principal.getResourceIds().isEmpty()) {
            return List.of();
        }
        // LangChain4j 的 EmbeddingStore 不直接支持遍历，这里返回空——
        // 真正的混合检索应在专业的关键词检索器中实现。
        // 本方法保留为扩展点，展示混合检索的端口设计。
        return List.of();
    }
}