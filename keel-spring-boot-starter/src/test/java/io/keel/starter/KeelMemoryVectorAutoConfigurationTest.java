package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import io.keel.memory.LongTermStore;
import io.keel.memory.VectorLongTermStore;

/**
 * L3 记忆向量后端装配测试（FR-52/FR-03）：
 * type=vector 时复用业务 EmbeddingModel bean 装配 {@link VectorLongTermStore}；
 * 缺 EmbeddingModel bean 时启动失败并给出明确修复提示。
 */
class KeelMemoryVectorAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KeelMemoryAutoConfiguration.class));

    @Test
    void vectorTypeWiresVectorLongTermStore() {
        runner.withPropertyValues(
                        "keel.memory.enabled=true",
                        "keel.memory.l3.type=vector")
                .withBean(EmbeddingModel.class, StubEmbeddingModel::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VectorLongTermStore.class);
                    assertThat(context.getBean(LongTermStore.class))
                            .isInstanceOf(VectorLongTermStore.class);
                });
    }

    @Test
    void vectorTypeWithoutEmbeddingModelBeanFailsFast() {
        runner.withPropertyValues(
                        "keel.memory.enabled=true",
                        "keel.memory.l3.type=vector")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("EmbeddingModel");
                });
    }

    /** 最小 embedding 桩：固定向量，只验证装配路径，不验证语义。 */
    static class StubEmbeddingModel implements EmbeddingModel {

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream()
                    .map(segment -> Embedding.from(new float[] {1f, 0f, 0f}))
                    .toList());
        }
    }
}
