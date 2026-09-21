package io.keel.starter;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.lettuce.core.RedisClient;
import io.keel.graph.spi.CheckpointPort;
import io.keel.graph.spi.TracePort;

/**
 * NFR-04 启动自检自动装配。
 *
 * <p>四个内置探针：模型 / 向量库 / Redis / Langfuse。向量与 Redis 探针依赖的
 * langchain4j、lettuce 在 starter 中是 optional 依赖，各用嵌套 @Configuration +
 * {@code @ConditionalOnClass} 守护，依赖缺席时对应探针不创建、其余探针照常。</p>
 *
 * <p>编排器 {@link KeelStartupSelfCheck} 在所有单例就绪后统一执行探测；
 * 模式由 keel.startup.connectivity-check 控制（off 默认零行为 / warn 告警 / strict
 * 启动失败）。业务可注册自己的 {@link StartupProbe} bean 纳入自检（NFR-05）。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(KeelProperties.class)
public class KeelStartupAutoConfiguration {

    @Bean
    @Order(1)
    public StartupProbe keelModelStartupProbe(KeelProperties properties,
            ObjectProvider<ChatModel> chatModels) {
        return new ModelStartupProbe(properties, chatModels);
    }

    @Bean
    @Order(4)
    public StartupProbe keelLangfuseStartupProbe(KeelProperties properties,
            ObjectProvider<TracePort> tracePorts) {
        return new LangfuseStartupProbe(properties, tracePorts);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(EmbeddingStore.class)
    static class VectorStoreProbeConfiguration {

        @Bean
        @Order(2)
        StartupProbe keelVectorStoreStartupProbe(KeelProperties properties,
                ObjectProvider<EmbeddingModel> embeddingModels,
                ObjectProvider<EmbeddingStore<?>> embeddingStores) {
            return new VectorStoreStartupProbe(properties, embeddingModels, embeddingStores);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisClient.class)
    static class RedisCheckpointProbeConfiguration {

        @Bean
        @Order(3)
        StartupProbe keelRedisCheckpointStartupProbe(KeelProperties properties,
                ObjectProvider<CheckpointPort> checkpointPorts) {
            return new RedisCheckpointStartupProbe(properties, checkpointPorts);
        }
    }

    @Bean
    public KeelStartupSelfCheck keelStartupSelfCheck(KeelProperties properties,
            List<StartupProbe> probes) {
        return new KeelStartupSelfCheck(properties, probes);
    }
}
