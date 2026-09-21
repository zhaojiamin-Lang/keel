package io.keel.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import io.keel.core.Citation;
import io.keel.core.KeelPrincipal;

/**
 * {@link LangChain4jRetrievalPort} 的隔离与容错测试（FR-40/FR-41/FR-42/G4）。
 * 使用确定性哈希向量，不依赖真实 embedding 服务。
 */
class LangChain4jRetrievalPortTest {

    private static final String TENANT = "tenant-1";
    private static final String OTHER_TENANT = "tenant-2";

    private HashingEmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> store;

    @BeforeEach
    void setUp() {
        embeddingModel = new HashingEmbeddingModel();
        store = new InMemoryEmbeddingStore<>();
    }

    @Test
    void returnsOnlyChunksOfAuthorizedResource() {
        index("c-p1", "doc-1", TENANT, "p1", "refund policy alpha details");
        index("c-p2", "doc-2", TENANT, "p2", "refund policy beta details");
        LangChain4jRetrievalPort port = newPort(5);

        List<Citation> citations = port.retrieve(
                "refund alpha", new KeelPrincipal(TENANT, "user", List.of("p1")));

        assertThat(citations).extracting(Citation::getChunkId).containsExactly("c-p1");
        Citation citation = citations.get(0);
        assertThat(citation.getSource()).isEqualTo("doc-1");
        assertThat(citation.getSnippet()).contains("refund policy alpha");
    }

    @Test
    void multipleResourceGrantsUseOrSemantics() {
        index("c-p1", "doc-1", TENANT, "p1", "shared refund topic alpha");
        index("c-p2", "doc-2", TENANT, "p2", "shared refund topic beta");
        LangChain4jRetrievalPort port = newPort(5);

        List<Citation> citations = port.retrieve(
                "shared refund topic", new KeelPrincipal(TENANT, "user", List.of("p1", "p2")));

        assertThat(citations).extracting(Citation::getChunkId)
                .containsExactlyInAnyOrder("c-p1", "c-p2");
    }

    @Test
    void chunksOfOtherTenantsAreIsolated() {
        index("c-other-tenant", "leak", OTHER_TENANT, "p1", "refund alpha secret details");
        LangChain4jRetrievalPort port = newPort(5);

        List<Citation> citations = port.retrieve(
                "refund alpha", new KeelPrincipal(TENANT, "user", List.of("p1")));

        assertThat(citations).isEmpty();
    }

    @Test
    void noResourceGrantsShortCircuitBeforeCallingEmbeddingOrStore() {
        index("c-p1", "doc-1", TENANT, "p1", "refund alpha details");
        EmbeddingModel exploding = segments -> {
            throw new AssertionError("无资源授权时不应调用 embedding 服务");
        };
        LangChain4jRetrievalPort port = new LangChain4jRetrievalPort(
                exploding, store, new PassThroughQueryRewriter(), 5);

        List<Citation> citations = port.retrieve(
                "refund alpha", new KeelPrincipal(TENANT, "user", List.of()));

        assertThat(citations).isEmpty();
        assertThatThrownBy(() -> AclScopeFilterFactory.create(
                new KeelPrincipal(TENANT, "user", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunksWithoutChunkIdAreDroppedInsteadOfBecomingCitations() {
        // 无 chunk_id 的脏数据片段
        TextSegment dirty = TextSegment.from("refund alpha dirty chunk",
                new Metadata(Map.of(
                        KeelChunkMetadata.SOURCE, "dirty-doc",
                        KeelChunkMetadata.TENANT_ID, TENANT,
                        KeelChunkMetadata.RESOURCE_ID, "p1")));
        store.add(embeddingModel.embed(dirty).content(), dirty);
        index("c-good", "good-doc", TENANT, "p1", "refund alpha good chunk");
        LangChain4jRetrievalPort port = newPort(5);

        List<Citation> citations = port.retrieve(
                "refund alpha", new KeelPrincipal(TENANT, "user", List.of("p1")));

        assertThat(citations).extracting(Citation::getChunkId).containsExactly("c-good");
    }

    @Test
    void maxResultsIsRespected() {
        for (int i = 1; i <= 6; i++) {
            index("c-" + i, "doc-" + i, TENANT, "p1", "shared refund topic number " + i);
        }
        LangChain4jRetrievalPort port = newPort(3);

        List<Citation> citations = port.retrieve(
                "shared refund topic", new KeelPrincipal(TENANT, "user", List.of("p1")));

        assertThat(citations).hasSize(3);
    }

    @Test
    void queryRewriterIsAppliedBeforeSearch() {
        index("c-1", "doc-1", TENANT, "p1", "refund alpha canonical wording");
        // 改写器把用户的暗号改写成索引中的真实措辞；默认透传改写器无法命中
        LangChain4jRetrievalPort port = new LangChain4jRetrievalPort(
                embeddingModel, store,
                (query, principal) -> "refund alpha canonical wording",
                5);

        List<Citation> citations = port.retrieve(
                "暗号 xyz", new KeelPrincipal(TENANT, "user", List.of("p1")));

        assertThat(citations).extracting(Citation::getChunkId).containsExactly("c-1");
    }

    @Test
    void embeddingFailureIsWrappedAsRetrievalException() {
        EmbeddingModel failing = segments -> {
            throw new RuntimeException("embed service down");
        };
        LangChain4jRetrievalPort port = new LangChain4jRetrievalPort(
                failing, store, new PassThroughQueryRewriter(), 5);

        assertThatThrownBy(() -> port.retrieve(
                "refund alpha", new KeelPrincipal(TENANT, "user", List.of("p1"))))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("查询向量化失败");
    }

    @Test
    void missingSourceMetadataDegradesToEmptySource() {
        TextSegment segment = TextSegment.from("refund alpha no source",
                new Metadata(Map.of(
                        KeelChunkMetadata.CHUNK_ID, "c-no-source",
                        KeelChunkMetadata.TENANT_ID, TENANT,
                        KeelChunkMetadata.RESOURCE_ID, "p1")));
        store.add(embeddingModel.embed(segment).content(), segment);
        LangChain4jRetrievalPort port = newPort(5);

        List<Citation> citations = port.retrieve(
                "refund alpha", new KeelPrincipal(TENANT, "user", List.of("p1")));

        assertThat(citations).extracting(Citation::getChunkId)
                .containsExactly("c-no-source");
        assertThat(citations.get(0).getSource()).isEmpty();
    }

    @Test
    void invalidMaxResultsIsRejected() {
        assertThatThrownBy(() -> new LangChain4jRetrievalPort(
                embeddingModel, store, new PassThroughQueryRewriter(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LangChain4jRetrievalPort newPort(int maxResults) {
        return new LangChain4jRetrievalPort(
                embeddingModel, store, new PassThroughQueryRewriter(), maxResults);
    }

    private void index(String chunkId, String source, String tenant, String resource, String text) {
        TextSegment segment = TextSegment.from(text, new Metadata(Map.of(
                KeelChunkMetadata.CHUNK_ID, chunkId,
                KeelChunkMetadata.SOURCE, source,
                KeelChunkMetadata.TENANT_ID, tenant,
                KeelChunkMetadata.RESOURCE_ID, resource)));
        Embedding embedding = embeddingModel.embed(segment).content();
        store.add(embedding, segment);
    }
}
