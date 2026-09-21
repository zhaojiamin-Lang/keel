package io.keel.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

/**
 * {@link KeelEmbeddingStoreFactory} 的构造与 fail-fast 校验测试（FR-40/FR-03）。
 * 只验证纯构造与连接前校验，不触达真实 PG / Milvus。
 */
class KeelEmbeddingStoreFactoryTest {

    @Test
    void inMemoryTypeBuildsInMemoryStore() {
        EmbeddingStore<TextSegment> store = KeelEmbeddingStoreFactory.create(
                KeelEmbeddingStoreConfig.builder()
                        .type(KeelEmbeddingStoreConfig.TYPE_IN_MEMORY)
                        .build());

        assertThat(store).isInstanceOf(InMemoryEmbeddingStore.class);
    }

    @Test
    void unknownTypeRejected() {
        assertThatThrownBy(() -> KeelEmbeddingStoreFactory.create(
                KeelEmbeddingStoreConfig.builder().type("elasticsearch").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elasticsearch");
    }

    @Test
    void pgvectorWithoutDimensionFailsBeforeConnecting() {
        assertThatThrownBy(() -> KeelEmbeddingStoreFactory.create(
                KeelEmbeddingStoreConfig.builder()
                        .type(KeelEmbeddingStoreConfig.TYPE_PGVECTOR)
                        .database("db").user("u").password("p")
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void pgvectorWithoutDatabaseFailsBeforeConnecting() {
        assertThatThrownBy(() -> KeelEmbeddingStoreFactory.create(
                KeelEmbeddingStoreConfig.builder()
                        .type(KeelEmbeddingStoreConfig.TYPE_PGVECTOR)
                        .dimension(128).user("u").password("p")
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database");
    }

    @Test
    void milvusWithoutDimensionFailsBeforeConnecting() {
        assertThatThrownBy(() -> KeelEmbeddingStoreFactory.create(
                KeelEmbeddingStoreConfig.builder()
                        .type(KeelEmbeddingStoreConfig.TYPE_MILVUS)
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }
}
