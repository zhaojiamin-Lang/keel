package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.keel.graph.spi.CheckpointPort;

/**
 * NFR-04 启动自检装配测试：
 * <ol>
 *   <li>off（默认）—— 探针零行为，Redis 不可达也不影响启动（静默降级是设计取舍）；</li>
 *   <li>warn —— 告警不阻断；</li>
 *   <li>strict —— 失败即启动失败，报告点名具体 bean 与配置键；</li>
 *   <li>覆盖模型缺 bean / Redis PING 失败 / Langfuse 缺 bean 与 host 不可达 /
 *       向量 bean 齐备性 / 未知模式 fail-closed / 业务自定义探针扩展。</li>
 * </ol>
 */
class KeelStartupSelfCheckTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    KeelCheckpointAutoConfiguration.class,
                    KeelStartupAutoConfiguration.class));

    private ApplicationContextRunner withChatModel(ApplicationContextRunner target) {
        return target.withBean(ChatModel.class, GraphRagTestFixtures.QueuedChatModel::new);
    }

    /** 必然连接拒绝的地址（127.0.0.1:1），保证测试不依赖真实 Redis，且探针快速失败 */
    private ApplicationContextRunner unreachableRedis(ApplicationContextRunner target) {
        return target.withPropertyValues(
                "keel.checkpoint.host=127.0.0.1",
                "keel.checkpoint.port=1",
                "keel.startup.probe-timeout=1s");
    }

    @Test
    void defaultOffDoesNotProbeAndKeepsSilentDegradation() {
        // Redis 必然不可达，但默认 off：不探测、启动照常（可选依赖静默降级）
        unreachableRedis(runner).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CheckpointPort.class);
        });
    }

    @Test
    void warnModeDoesNotBlockStartup() {
        unreachableRedis(runner).withPropertyValues("keel.startup.connectivity-check=warn")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CheckpointPort.class);
                });
    }

    @Test
    void strictModeFailsStartupNamingRedisBeanAndConfigKeys() {
        unreachableRedis(withChatModel(runner))
                .withPropertyValues("keel.startup.connectivity-check=strict")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // NFR-04 核心：连不通要报哪个 bean —— 点名 bean + 配置键
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("keelCheckpointPort")
                            .hasStackTraceContaining("RedisCheckpointPort")
                            .hasStackTraceContaining("keel.checkpoint.host");
                });
    }

    @Test
    void strictModeFailsNamingChatModelWhenMissing() {
        withChatModel(runner).withPropertyValues(
                        "keel.startup.connectivity-check=strict",
                        "keel.checkpoint.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
        // 同一配置去掉 ChatModel bean：模型探针点名 ChatModel
        runner.withPropertyValues(
                        "keel.startup.connectivity-check=strict",
                        "keel.checkpoint.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("ChatModel")
                            .hasStackTraceContaining("keel.model.enabled");
                });
    }

    @Test
    void strictModeFailsWhenLangfuseEnabledButNoTracePort() {
        withChatModel(runner).withPropertyValues(
                        "keel.startup.connectivity-check=strict",
                        "keel.checkpoint.enabled=false",
                        "keel.langfuse.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("TracePort")
                            .hasStackTraceContaining("keel.langfuse.public-key");
                });
    }

    @Test
    void strictModeFailsWhenLangfuseHostUnreachable() {
        // 走真实装配链：KeelLangfuseAutoConfiguration 按 keel.langfuse.* 创建 LangfuseTracePort，
        // 探针探活同一 host（必然拒绝的 127.0.0.1:1），保证测试确定性且不打真实外网
        withChatModel(runner)
                .withConfiguration(AutoConfigurations.of(KeelLangfuseAutoConfiguration.class))
                .withPropertyValues(
                        "keel.startup.connectivity-check=strict",
                        "keel.checkpoint.enabled=false",
                        "keel.langfuse.enabled=true",
                        "keel.langfuse.host=http://127.0.0.1:1",
                        "keel.langfuse.public-key=pk-test",
                        "keel.langfuse.secret-key=sk-test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("keelLangfuseTracePort")
                            .hasStackTraceContaining("健康检查失败")
                            .hasStackTraceContaining("keel.langfuse.host");
                });
    }

    @Test
    void strictModeFailsWhenStoreTypeConfiguredWithoutEmbeddingModel() {
        withChatModel(runner).withPropertyValues(
                        "keel.startup.connectivity-check=strict",
                        "keel.checkpoint.enabled=false",
                        "keel.retrieval.store.type=pgvector")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("EmbeddingModel")
                            .hasStackTraceContaining("keel.retrieval.store.type");
                });
    }

    @Test
    void strictModeAllGreenStartsNormally() {
        withChatModel(runner).withPropertyValues(
                        "keel.startup.connectivity-check=strict",
                        "keel.checkpoint.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void businessCanRegisterCustomStartupProbe() {
        withChatModel(runner)
                .withBean(StartupProbe.class, () -> (StartupProbe) () -> "自定义探针: 业务子系统不可用")
                .withPropertyValues(
                        "keel.startup.connectivity-check=strict",
                        "keel.checkpoint.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("自定义探针: 业务子系统不可用");
                });
    }

    @Test
    void unknownModeFailsFastAtConfigurationTime() {
        // fail-closed：模式拼写错误不允许自检静默失效
        withChatModel(runner).withPropertyValues(
                        "keel.startup.connectivity-check=yolo",
                        "keel.checkpoint.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("未知 keel.startup.connectivity-check");
                });
    }

    @Test
    void probesReportedInDeterministicOrder() {
        // 模型缺失 + Redis 不可达 + Langfuse 缺 bean 三项同时失败：报告按 模型→Redis→Langfuse 排序
        unreachableRedis(runner).withPropertyValues(
                        "keel.startup.connectivity-check=strict",
                        "keel.langfuse.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String report = reportMessageOf(context.getStartupFailure());
                    assertThat(report)
                            .contains("3 个子系统异常")
                            .containsSubsequence("模型:", "Redis:", "Langfuse:");
                });
    }

    /** 自检报告在 IllegalState 消息里；外层可能被 BeanCreationException 包一层，向下找 */
    private String reportMessageOf(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("启动自检")) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return failure == null ? "" : String.valueOf(failure.getMessage());
    }
}
