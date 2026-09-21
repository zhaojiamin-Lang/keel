package io.keel.rag;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.keel.core.Citation;
import io.keel.core.KeelPrincipal;
import io.keel.graph.spi.RetrievalPort;

/**
 * 基于 LangChain4j 标准组件（{@link EmbeddingModel} + {@link EmbeddingStore}）的
 * {@link RetrievalPort} 实现（FR-40/FR-41/FR-42）。
 *
 * <p>执行链路：查询改写 → 无资源授权短路 → embed 查询 → 带强制 ACL Filter 的 kNN →
 * 映射 Citation。生产环境的 Milvus/PGVector 等存储只需作为 {@link EmbeddingStore}
 * bean 注入，本类不绑定任何具体存储。</p>
 */
public class LangChain4jRetrievalPort implements RetrievalPort {

    /** 默认召回条数（FR-40 P0 固定 topK）；需要参数化时走显式配置，不做隐式魔法。 */
    public static final int DEFAULT_MAX_RESULTS = 5;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final QueryRewriter queryRewriter;
    private final int maxResults;
    private final Reranker reranker;

    public LangChain4jRetrievalPort(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore) {
        this(embeddingModel, embeddingStore, new PassThroughQueryRewriter(), DEFAULT_MAX_RESULTS, new NoOpReranker());
    }

    public LangChain4jRetrievalPort(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            QueryRewriter queryRewriter,
            int maxResults) {
        this(embeddingModel, embeddingStore, queryRewriter, maxResults, new NoOpReranker());
    }

    public LangChain4jRetrievalPort(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            QueryRewriter queryRewriter,
            int maxResults,
            Reranker reranker) {
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults 必须为正整数: " + maxResults);
        }
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.queryRewriter = queryRewriter == null ? new PassThroughQueryRewriter() : queryRewriter;
        this.maxResults = maxResults;
        this.reranker = reranker == null ? new NoOpReranker() : reranker;
    }

    @Override
    public List<Citation> retrieve(String query, KeelPrincipal principal) {
        // fail-closed：无身份（没有身份边界就不给数据）或无任何资源授权，
        // 直接空召回，且不向向量库发出请求（最小暴露面）
        if (principal == null || principal.getResourceIds().isEmpty()) {
            return List.of();
        }

        String rewritten = queryRewriter.rewrite(query, principal);

        Embedding queryEmbedding = embed(rewritten);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .filter(AclScopeFilterFactory.create(principal))
                .maxResults(maxResults)
                .build();

        EmbeddingSearchResult<TextSegment> result = search(request);

        List<Citation> citations = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            TextSegment segment = match.embedded();
            Metadata metadata = segment.metadata();
            String chunkId = metadata.getString(KeelChunkMetadata.CHUNK_ID);
            // FR-42：缺 chunk_id 的片段无法被引用核验，直接丢弃
            if (chunkId == null || chunkId.isBlank()) {
                continue;
            }
            String source = metadata.getString(KeelChunkMetadata.SOURCE);
            citations.add(new Citation(
                    chunkId,
                    source == null ? "" : source,
                    segment.text()));
        }
        // FR-41：Rerank 重排序
        return reranker.rerank(rewritten, citations);
    }

    private Embedding embed(String query) {
        try {
            Response<Embedding> response = embeddingModel.embed(query);
            return response.content();
        } catch (RuntimeException exception) {
            throw new RetrievalException(
                    "查询向量化失败: " + exception.getMessage(), exception);
        }
    }

    private EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        try {
            return embeddingStore.search(request);
        } catch (RuntimeException exception) {
            throw new RetrievalException(
                    "向量库检索失败: " + exception.getMessage(), exception);
        }
    }
}
