package io.keel.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import io.keel.core.Citation;
import io.keel.core.KeelPrincipal;

/**
 * {@link KeelDocumentIngestor} 入库 → 检索的闭环测试（FR-40/FR-41/FR-42/FR-44）：
 * 入库时写齐隔离与引用元数据，检索时 ACL 过滤只命中授权租户 + 资源。
 */
class KeelDocumentIngestorTest {

    private static final String TENANT = "tenant-1";

    private HashingEmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> store;
    private KeelDocumentIngestor ingestor;

    @BeforeEach
    void setUp() {
        embeddingModel = new HashingEmbeddingModel();
        store = new InMemoryEmbeddingStore<>();
        ingestor = new KeelDocumentIngestor(embeddingModel, store);
    }

    @Test
    void ingestWritesChunksWithIsolationAndCitationMetadata() {
        List<String> chunkIds = ingestor.ingest(
                "退款政策：支持 7 天无理由退款。".repeat(20),
                "refund-wiki", TENANT, "doc-1", "v3");

        assertThat(chunkIds).isNotEmpty();
        assertThat(chunkIds.get(0)).startsWith("refund-wiki#");

        // 检索：同租户 + 授权资源能命中，citation 带 chunk_id 与 source
        LangChain4jRetrievalPort port = new LangChain4jRetrievalPort(
                embeddingModel, store, new PassThroughQueryRewriter(), 5);
        List<Citation> citations = port.retrieve(
                "退款政策 无理由退款",
                new KeelPrincipal(TENANT, "user", List.of("doc-1")));

        assertThat(citations).isNotEmpty();
        assertThat(citations.get(0).getSource()).isEqualTo("refund-wiki");
        assertThat(citations.get(0).getChunkId()).startsWith("refund-wiki#");
    }

    @Test
    void chunksAreIsolatedByTenantAndResource() {
        ingestor.ingest("退款政策 无理由退款", "refund-wiki", TENANT, "doc-1", null);
        ingestor.ingest("退款政策 其他租户", "other-wiki", "tenant-2", "doc-1", null);

        LangChain4jRetrievalPort port = new LangChain4jRetrievalPort(
                embeddingModel, store, new PassThroughQueryRewriter(), 10);

        List<Citation> citations = port.retrieve(
                "退款政策",
                new KeelPrincipal(TENANT, "user", List.of("doc-1")));

        assertThat(citations).isNotEmpty();
        assertThat(citations).allSatisfy(c ->
                assertThat(c.getSource()).isEqualTo("refund-wiki"));
    }

    @Test
    void docVersionIsPersistedAsMetadata() {
        ingestor.ingest("退款政策", "refund-wiki", TENANT, "doc-1", "v7");

        // 从 store 直接取回片段核对 doc_version 元数据已写入（FR-44）
        TextSegment first = store.search(dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                        .queryEmbedding(embeddingModel.embed("退款政策").content())
                        .filter(AclScopeFilterFactory.create(
                                new KeelPrincipal(TENANT, "user", List.of("doc-1"))))
                        .maxResults(1)
                        .build())
                .matches().get(0).embedded();

        assertThat(first.metadata().getString(KeelChunkMetadata.DOC_VERSION)).isEqualTo("v7");
    }

    @Test
    void missingTenantOrResourceFailsFast() {
        assertThatThrownBy(() -> ingestor.ingest(
                "内容", "s", null, "doc-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> ingestor.ingest(
                "内容", "s", TENANT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceId");
    }
}
