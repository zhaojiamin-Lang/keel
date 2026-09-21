package io.keel.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.keel.rag.KeelDocumentIngestor;
import io.keel.rag.KeelEmbeddingStoreConfig;
import io.keel.rag.KeelEmbeddingStoreFactory;

/**
 * 生产向量库自动装配（FR-40/FR-44）：
 * <ul>
 *   <li>仅当 keel.retrieval.store.type 显式配置且 classpath 存在 keel-rag-langchain4j 时，
 *       按配置创建 {@link EmbeddingStore} bean（in-memory / pgvector / milvus）；</li>
 *   <li>type 未配置或业务自备 EmbeddingStore bean 时不创建（@ConditionalOnMissingBean），
 *       沿用「业务注入 store」的既有路径；</li>
 *   <li>store + EmbeddingModel 同时存在时，再装配 {@link KeelDocumentIngestor} 供业务入库。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass({KeelEmbeddingStoreFactory.class, EmbeddingStore.class})
@ConditionalOnProperty(prefix = "keel.retrieval.store", name = "type")
@EnableConfigurationProperties(KeelProperties.class)
public class KeelRetrievalStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EmbeddingStore.class)
    public EmbeddingStore<TextSegment> keelEmbeddingStore(KeelProperties properties) {
        KeelProperties.Retrieval.Store store = properties.getRetrieval().getStore();
        KeelEmbeddingStoreConfig config = KeelEmbeddingStoreConfig.builder()
                .type(store.getType())
                .dimension(store.getDimension())
                .host(store.getHost())
                .port(store.getPort())
                .database(store.getDatabase())
                .user(store.getUser())
                .password(store.getPassword())
                .table(store.getTable())
                .collectionName(store.getCollectionName())
                .uri(store.getUri())
                .token(store.getToken())
                .build();
        return KeelEmbeddingStoreFactory.create(config);
    }

    @Bean
    @ConditionalOnBean({EmbeddingModel.class, EmbeddingStore.class})
    @ConditionalOnMissingBean(KeelDocumentIngestor.class)
    public KeelDocumentIngestor keelDocumentIngestor(
            EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        return new KeelDocumentIngestor(embeddingModel, embeddingStore);
    }
}
