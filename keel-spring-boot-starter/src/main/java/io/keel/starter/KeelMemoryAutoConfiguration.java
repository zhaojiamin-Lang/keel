package io.keel.starter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import dev.langchain4j.model.embedding.EmbeddingModel;

import io.keel.graph.spi.MemoryPort;
import io.keel.memory.EpisodicStore;
import io.keel.memory.InMemoryEpisodicStore;
import io.keel.memory.InMemoryLongTermStore;
import io.keel.memory.JdbcLongTermStore;
import io.keel.memory.LongTermStore;
import io.keel.memory.MongoEpisodicStore;
import io.keel.memory.TieredMemoryPort;
import io.keel.memory.VectorLongTermStore;

/**
 * L2/L3 记忆自动装配（FR-51/FR-52/FR-54）：
 * <ul>
 *   <li>keel.memory.enabled=true 且 classpath 存在 keel-memory 才生效（默认关闭，零负担）；</li>
 *   <li>L2 默认 in-memory，type=mongo 时走 Mongo（缺驱动抛明确错误 FR-03）；</li>
 *   <li>L3 默认 in-memory，type=jdbc 时走 PG、type=vector 时走向量后端
 *       （复用业务 EmbeddingModel bean，缺 bean/依赖抛明确错误 FR-03）；</li>
 *   <li>EpisodicStore / LongTermStore / MemoryPort 层层 @ConditionalOnMissingBean 可覆盖。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass({MemoryPort.class, TieredMemoryPort.class})
@ConditionalOnProperty(prefix = "keel.memory", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(KeelProperties.class)
public class KeelMemoryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EpisodicStore.class)
    public EpisodicStore keelEpisodicStore(KeelProperties properties) {
        KeelProperties.Memory.L2 l2 = properties.getMemory().getL2();
        if ("mongo".equalsIgnoreCase(l2.getType())) {
            try {
                return new MongoEpisodicStore(
                        l2.getConnectionString(), l2.getDatabase(), l2.getCollection());
            } catch (NoClassDefFoundError error) {
                throw new IllegalStateException(
                        "L2 记忆选择 mongo 但 classpath 缺少 mongodb-driver-sync，请补充依赖", error);
            }
        }
        return new InMemoryEpisodicStore();
    }

    @Bean
    @ConditionalOnMissingBean(LongTermStore.class)
    public LongTermStore keelLongTermStore(KeelProperties properties,
            ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        KeelProperties.Memory.L3 l3 = properties.getMemory().getL3();
        if ("jdbc".equalsIgnoreCase(l3.getType())) {
            try {
                return new JdbcLongTermStore(l3.getJdbcUrl(), l3.getUser(), l3.getPassword());
            } catch (NoClassDefFoundError error) {
                throw new IllegalStateException(
                        "L3 记忆选择 jdbc 但 classpath 缺少 postgresql 驱动，请补充依赖", error);
            }
        }
        if ("vector".equalsIgnoreCase(l3.getType())) {
            try {
                // FR-52：向量后端复用业务容器提供的 EmbeddingModel bean（与 RAG 同一模型即可）；
                // EmbeddingStore 默认进程内实现（dev/test），生产建议业务自注册
                // LongTermStore bean 注入持久化向量库（pgvector / milvus）覆盖。
                EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
                if (embeddingModel == null) {
                    throw new IllegalStateException(
                            "L3 记忆选择 vector 但容器中没有 EmbeddingModel bean（FR-03）："
                                    + "请业务注册一个 embedding 模型 bean 后重试");
                }
                return new VectorLongTermStore(embeddingModel);
            } catch (NoClassDefFoundError error) {
                throw new IllegalStateException(
                        "L3 记忆选择 vector 但 classpath 缺少 langchain4j，请补充依赖", error);
            }
        }
        return new InMemoryLongTermStore();
    }

    @Bean
    @ConditionalOnMissingBean(MemoryPort.class)
    public MemoryPort keelMemoryPort(EpisodicStore episodicStore, LongTermStore longTermStore) {
        return new TieredMemoryPort(episodicStore, longTermStore);
    }
}
