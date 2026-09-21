package io.keel.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import io.keel.core.Citation;
import io.keel.core.KeelPrincipal;

/**
 * FR-41：混合检索——向量召回 + 关键词召回合并去重（按 chunkId），再经 Rerank 输出。
 */
class KeywordHybridRetrieverTest {

    private static final String TENANT = "tenant-1";

    private HashingEmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> store;

    /** 记录型 Reranker：捕获输入，原样返回，便于断言合并结果传入了 rerank。 */
    private static final class RecordingReranker implements Reranker {
        private String seenQuery;
        private List<Citation> seenCitations = List.of();

        @Override
        public List<Citation> rerank(String query, List<Citation> citations) {
            this.seenQuery = query;
            this.seenCitations = citations;
            return citations;
        }
    }

    @BeforeEach
    void setUp() {
        embeddingModel = new HashingEmbeddingModel();
        store = new InMemoryEmbeddingStore<>();
    }

    @Test
    void vectorResultsPassThroughAndReachReranker() {
        index("c-1", "doc-1", TENANT, "p1", "refund policy alpha details");
        index("c-2", "doc-2", TENANT, "p1", "shipping policy beta details");
        RecordingReranker reranker = new RecordingReranker();
        KeywordHybridRetriever retriever = newRetriever(reranker);

        List<Citation> citations = retriever.retrieve(
                "refund alpha", new KeelPrincipal(TENANT, "user", List.of("p1")));

        assertThat(citations).extracting(Citation::getChunkId)
                .containsExactlyInAnyOrder("c-1", "c-2");
        assertThat(reranker.seenQuery).isEqualTo("refund alpha");
        assertThat(reranker.seenCitations).hasSize(2);
    }

    @Test
    void duplicateChunkIdsFromVectorRecallAreDeduplicated() {
        // 两个不同片段带同一 chunkId（脏数据/重复入库）：合并阶段必须去重
        index("c-dup", "doc-1", TENANT, "p1", "refund alpha copy one");
        index("c-dup", "doc-2", TENANT, "p1", "refund alpha copy two");
        KeywordHybridRetriever retriever = newRetriever(new NoOpReranker());

        List<Citation> citations = retriever.retrieve(
                "refund alpha", new KeelPrincipal(TENANT, "user", List.of("p1")));

        assertThat(citations).extracting(Citation::getChunkId).containsExactly("c-dup");
    }

    @Test
    void aclFilterStillAppliesToVectorLeg() {
        index("c-p1", "doc-1", TENANT, "p1", "refund alpha details");
        index("c-p2", "doc-2", TENANT, "p2", "refund alpha other project");
        KeywordHybridRetriever retriever = newRetriever(new NoOpReranker());

        List<Citation> citations = retriever.retrieve(
                "refund alpha", new KeelPrincipal(TENANT, "user", List.of("p1")));

        assertThat(citations).extracting(Citation::getChunkId).containsExactly("c-p1");
    }

    @Test
    void noResourceGrantsYieldEmptyResult() {
        index("c-p1", "doc-1", TENANT, "p1", "refund alpha details");
        KeywordHybridRetriever retriever = newRetriever(new NoOpReranker());

        List<Citation> citations = retriever.retrieve(
                "refund alpha", new KeelPrincipal(TENANT, "user", List.of()));

        assertThat(citations).isEmpty();
    }

    @Test
    void nullRerankerFallsBackToNoOp() {
        // 构造器传 null reranker 时应回退 NoOp 而不是 NPE
        KeywordHybridRetriever retriever = new KeywordHybridRetriever(
                embeddingModel, store, new PassThroughQueryRewriter(), 5, null);
        index("c-1", "doc-1", TENANT, "p1", "refund alpha details");

        List<Citation> citations = retriever.retrieve(
                "refund alpha", new KeelPrincipal(TENANT, "user", List.of("p1")));

        assertThat(citations).extracting(Citation::getChunkId).containsExactly("c-1");
    }

    private KeywordHybridRetriever newRetriever(Reranker reranker) {
        return new KeywordHybridRetriever(
                embeddingModel, store, new PassThroughQueryRewriter(), 5, reranker);
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
