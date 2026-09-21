package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import io.keel.client.DefaultPrincipalResolver;
import io.keel.client.IdempotencyKeys;
import io.keel.client.KeelAgentClient;
import io.keel.client.PrincipalResolver;
import io.keel.client.SessionIds;
import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.KeelPrincipal;
import io.keel.graph.ToolCall;
import io.keel.graph.ToolObservation;
import io.keel.graph.spi.ToolPort;

/**
 * 接入适配层装配测试：业务注册一个 {@link PrincipalResolver} bean 即被采用，
 * 不注册时用 {@link DefaultPrincipalResolver} 兜底（零配置可启动）。
 *
 * <p>重点覆盖「接入便利」是否真的成立：注入 {@link KeelAgentClient} 就能跑通一次
 * 请求，且身份 / 会话 / 幂等键三样都由适配层按统一规则装配出来。</p>
 *
 * <p>断言全部走容器里的 bean，不自己 new 组件——否则测的就不是装配结果了。
 * 用 {@link CapturingAgent} 当 {@link KeelAgent} 是刻意的：这里关心的是
 * <b>适配层装配出的请求字段</b>，不是模型调度细节，捕获型 agent 让断言直接落在
 * 真正被测的那一层。</p>
 */
class KeelClientAutoConfigurationTest {

    /** 只装配「装配适配层」相关的两个自动配置，配一个可替换的 ChatModel 让内核能起。 */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        KeelAutoConfiguration.class, KeelClientAutoConfiguration.class))
                .withBean(ChatModel.class, GraphRagTestFixtures.QueuedChatModel::new)
                .withPropertyValues("keel.agent.mode=graph");
    }

    @Test
    void businessPrincipalResolverIsUsedWhenRegistered() {
        runner()
                .withUserConfiguration(BusinessIdentityApplication.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 业务 bean 覆盖默认——不应该同时存在默认实现
                    assertThat(context.getBean(PrincipalResolver.class))
                            .isInstanceOf(BusinessPrincipalResolver.class);

                    GraphRagTestFixtures.QueuedChatModel chatModel =
                            (GraphRagTestFixtures.QueuedChatModel) context.getBean(ChatModel.class);
                    chatModel.reset(
                            "{\"action\":\"ANSWER\",\"answer\":\"pong\"}",
                            "{\"verdict\":\"APPROVED\"}");

                    // 业务侧只需这一行（不再手拼 AgentRequest）
                    AgentResult result =
                            context.getBean(KeelAgentClient.class).chat("ping", "chat");

                    assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
                    // 身份来自业务 resolver，且请求确实被转交到内核
                    assertThat(BusinessPrincipalResolver.lastResolved).isNotNull();
                });
    }

    @Test
    void defaultPrincipalResolverIsUsedWhenNoneRegistered() {
        runner()
                .withPropertyValues(
                        "keel.principal.tenant-id=tenant-config",
                        "keel.principal.subject-id=user-config",
                        "keel.principal.resource-ids[0]=project-config")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PrincipalResolver resolver = context.getBean(PrincipalResolver.class);
                    assertThat(resolver).isInstanceOf(DefaultPrincipalResolver.class);

                    KeelPrincipal principal = resolver.resolve();
                    assertThat(principal.getTenantId()).isEqualTo("tenant-config");
                    assertThat(principal.getSubjectId()).isEqualTo("user-config");
                    assertThat(principal.getResourceIds()).containsExactly("project-config");
                    assertThat(context).hasSingleBean(KeelAgentClient.class);
                });
    }

    @Test
    void defaultPrincipalResolverFallsBackToBuiltInDefaults() {
        // 零配置：不配 keel.principal.* 也应能启动并给出一个非空身份
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            KeelPrincipal principal = context.getBean(PrincipalResolver.class).resolve();
            assertThat(principal.getTenantId()).isEqualTo("default");
            assertThat(principal.getSubjectId()).isEqualTo("anonymous");
        });
    }

    @Test
    void clientCanBeDisabledToKeepLegacyUsage() {
        // keel.client.enabled=false 时不创建适配层，业务退回自己拼 AgentRequest
        // ——保证引入适配层不破坏已有接入方式
        runner()
                .withPropertyValues("keel.client.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(KeelAgentClient.class);
                    // 内核本身不受影响，仍可注入 KeelAgent 手工使用
                    assertThat(context).hasBean("guardedGraphKeelAgent");
                });
    }

    /**
     * 自定义幂等键前缀：用「只有 stub agent、没有 ChatModel」的上下文，
     * 断言 client 装配出的键前缀真的来自 {@code keel.client.idempotency-key-prefix}。
     *
     * <p><b>为什么必须另起一个不含 {@code KeelAutoConfiguration} 的上下文：</b>
     * 一旦装配了内核，{@code @Primary guardedGraphKeelAgent} 就是 client 拿到的那一个，
     * 注册 stub agent 也覆盖不掉它——想抓 client 实际发出的请求字段，
     * 就只能让 stub 成为容器里唯一的 {@link KeelAgent}。</p>
     */
    @Test
    void customIdempotencyKeyPrefixIsApplied() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KeelClientAutoConfiguration.class))
                .withBean(CapturingAgent.class, CapturingAgent::new)
                .withPropertyValues(
                        "keel.client.idempotency-key-prefix=myapp",
                        "keel.principal.tenant-id=tenant-config",
                        "keel.principal.subject-id=user-config")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(KeelAgentClient.class).chat("ping", "chat");

                    CapturingAgent capturing = context.getBean(CapturingAgent.class);
                    assertThat(capturing.lastRequest)
                            .as("stub agent 是容器里唯一的 KeelAgent，client 必须调到它")
                            .isNotNull();
                    assertThat(capturing.lastRequest.getIdempotencyKey())
                            .isEqualTo("myapp:tenant-config:" + SessionIds.fallback(
                                    "tenant-config", "user-config"));
                });
    }

    /**
     * 前缀留空必须回退默认值，而不是拼出 {@code :tenant:session} 这种坏键。
     * 同样走 stub agent 抓真实请求，避免断言脱离被测对象。
     */
    @Test
    void blankIdempotencyKeyPrefixFallsBackToDefault() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KeelClientAutoConfiguration.class))
                .withBean(CapturingAgent.class, CapturingAgent::new)
                .withPropertyValues(
                        "keel.client.idempotency-key-prefix=",
                        "keel.principal.tenant-id=tenant-config",
                        "keel.principal.subject-id=user-config")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(KeelAgentClient.class).chat("ping", "chat");

                    CapturingAgent capturing = context.getBean(CapturingAgent.class);
                    assertThat(capturing.lastRequest).isNotNull();
                    assertThat(capturing.lastRequest.getIdempotencyKey())
                            .startsWith(IdempotencyKeys.DEFAULT_PREFIX + ":")
                            .isEqualTo("keel:tenant-config:" + SessionIds.fallback(
                                    "tenant-config", "user-config"));
                });
    }

    /**
     * 业务自备 {@link KeelAgent} 时 client 照样装配——条件只要求「有 agent」，
     * 不要求这个 agent 是 starter 产出的。
     */
    @Test
    void clientIsCreatedWhenBusinessProvidesOwnAgent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KeelClientAutoConfiguration.class))
                .withBean(CapturingAgent.class, CapturingAgent::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(KeelAgentClient.class);
                });
    }

    /**
     * 容器里没有任何 {@link KeelAgent} 时 client 干脆缺席（而不是构造失败拖垮启动）
     * ——这正是 {@code @ConditionalOnBean(KeelAgent.class)} 要保证的。
     */
    @Test
    void clientIsAbsentWhenNoAgentExists() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KeelClientAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(KeelAgentClient.class);
                });
    }

    static class BusinessIdentityApplication {

        @Bean
        PrincipalResolver businessPrincipalResolver() {
            return new BusinessPrincipalResolver();
        }
    }

    static class BusinessPrincipalResolver implements PrincipalResolver {

        static KeelPrincipal lastResolved;

        @Override
        public KeelPrincipal resolve() {
            lastResolved = new KeelPrincipal("tenant-biz", "user-biz", List.of("project-biz"));
            return lastResolved;
        }
    }

    /**
     * 捕获型 agent：记录收到的请求，用来断言 client 装配出的字段。
     *
     * <p>注意它<b>取代不了</b> {@code ChatModel}：{@code graphKeelAgent} 依赖的是
     * {@code SpringAiModelPort}，与容器里有没有别的 {@link KeelAgent} bean 无关，
     * 所以 {@code guardedGraphKeelAgent}（@Primary）依然是 client 拿到的那一个。
     * 本类因此只用于「业务自备 agent 时 client 照常装配」这类装配断言，
     * 不用于捕获请求字段。</p>
     */
    static class CapturingAgent implements KeelAgent {

        volatile AgentRequest lastRequest;

        @Override
        public AgentResult run(AgentRequest request) {
            this.lastRequest = request;
            return AgentResult.builder().status(AgentStatus.SUCCESS).text("ok").build();
        }
    }

    /**
     * 让 graph 装配走「有工具可用」分支的最小 ToolPort。
     * 保留夹具以便后续测工具路径；当前用例不依赖它。
     */
    static class CapturingToolPort implements ToolPort {

        @Override
        public ToolObservation invoke(ToolCall call, KeelPrincipal principal) {
            return ToolObservation.ok(call.getTool(), "{\"result\":\"ok\"}");
        }

        @Override
        public boolean isWriteTool(String tool) {
            return false;
        }
    }
}
