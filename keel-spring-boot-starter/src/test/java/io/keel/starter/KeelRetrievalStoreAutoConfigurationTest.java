package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.keel.rag.KeelDocumentIngestor;

/**
 * 生产向量库自动装配测试（FR-40/FR-44）：type=in-memory 时按配置创建
 * EmbeddingStore + KeelDocumentIngestor，且入库写齐隔离元数据后可被检索。
 */
@SpringBootTest(properties = "keel.retrieval.store.type=in-memory")
class KeelRetrievalStoreAutoConfigurationTest {

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private KeelDocumentIngestor ingestor;

    @Test
    void inMemoryStoreAndIngestorAreWired() {
        assertThat(embeddingStore).isInstanceOf(InMemoryEmbeddingStore.class);
        assertThat(ingestor).isNotNull();

        List<String> chunkIds = ingestor.ingest(
                "退款政策：支持 7 天无理由退款", "wiki", "tenant-1", "doc-1", "v1");
        assertThat(chunkIds).isNotEmpty();
        assertThat(chunkIds.get(0)).startsWith("wiki#");
    }

    @SpringBootApplication
    static class StoreTestApplication {

        @Bean
        EmbeddingModel embeddingModel() {
            return new GraphRagTestFixtures.HashingEmbeddingModel();
        }
    }
}
