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
import io.keel.graph.spi.RetrievalPort;
import io.keel.rag.LangChain4jRetrievalPort;
import io.keel.rag.PassThroughQueryRewriter;
import io.keel.rag.QueryRewriter;

/**
 * RAG 检索自动装配（FR-15/FR-40）：
 * <ul>
 *   <li>classpath 同时存在 keel-rag-langchain4j 与 langchain4j 才生效（optional 依赖）；</li>
 *   <li>容器中存在 EmbeddingModel + EmbeddingStore 两个 bean 才装配 RetrievalPort；</li>
 *   <li>keel.retrieval.enabled=false 可显式关闭；</li>
 *   <li>QueryRewriter 允许业务覆盖（@ConditionalOnMissingBean）。</li>
 * </ul>
 * 租户 / 资源隔离由适配层 AclScopeFilterFactory 在每次检索时代码化强制（FR-41），
 * 不提供任何配置开关。
 */
@AutoConfiguration
@ConditionalOnClass({LangChain4jRetrievalPort.class, EmbeddingModel.class, EmbeddingStore.class})
@ConditionalOnProperty(prefix = "keel.retrieval", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KeelProperties.class)
public class KeelRagAutoConfiguration {

    /** 默认查询改写：直通（多轮 query rewrite 留给后续步骤，不提前做） */
    @Bean
    @ConditionalOnMissingBean(QueryRewriter.class)
    public QueryRewriter keelQueryRewriter() {
        return new PassThroughQueryRewriter();
    }

    /**
     * 只有业务容器提供 EmbeddingModel + EmbeddingStore（如 InMemoryEmbeddingStore /
     * PgVectorEmbeddingStore）时才装配，缺一个都不静默创建假实现。
     */
    @Bean
    @ConditionalOnBean({EmbeddingModel.class, EmbeddingStore.class})
    @ConditionalOnMissingBean(RetrievalPort.class)
    public RetrievalPort keelRetrievalPort(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            QueryRewriter queryRewriter,
            KeelProperties properties) {
        return new LangChain4jRetrievalPort(
                embeddingModel,
                embeddingStore,
                queryRewriter,
                properties.getRetrieval().getMaxResults());
    }
}
