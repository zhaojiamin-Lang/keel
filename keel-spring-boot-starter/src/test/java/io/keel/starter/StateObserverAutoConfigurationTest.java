package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelPrincipal;
import io.keel.graph.NodeName;
import io.keel.graph.spi.StateObserver;

/**
 * FR-14 零配置装配测试：业务只注册一个 {@link StateObserver} bean，
 * {@code graphKeelAgent} 就应自动接入它（无需手动 builder 装配）。
 *
 * <p>覆盖两种情形：注册 bean → 每步收到快照；未注册 bean → bean 存在但观察者
 * 保持静默（内核零额外开销）。</p>
 */
class StateObserverAutoConfigurationTest {

    @Test
    void registeredObserverBeanReceivesStepSnapshotsWithoutManualWiring() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KeelAutoConfiguration.class))
                .withBean(ChatModel.class, GraphRagTestFixtures.QueuedChatModel::new)
                .withUserConfiguration(ObserverApplication.class)
                .withPropertyValues("keel.agent.mode=graph")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RecordingStateObserver observer = context.getBean(RecordingStateObserver.class);
                    GraphRagTestFixtures.QueuedChatModel chatModel =
                            (GraphRagTestFixtures.QueuedChatModel) context.getBean(ChatModel.class);
                    chatModel.reset(
                            "{\"action\":\"ANSWER\",\"answer\":\"pong\"}",
                            "{\"verdict\":\"APPROVED\"}");

                    AgentResult result = context.getBean(io.keel.core.KeelAgent.class).run(
                            AgentRequest.builder()
                                    .principal(new KeelPrincipal("t", "u", List.of()))
                                    .skill("chat")
                                    .input("ping")
                                    .build());

                    assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
                    // 零配置接入：业务没碰 builder，快照照样推过来
                    assertThat(observer.nodes).isNotEmpty();
                    assertThat(observer.nodes).contains(NodeName.PLAN, NodeName.CRITIC, NodeName.GUARD);
                    assertThat(observer.snapshots.get(0).getRequest()).isNotNull();
                    assertThat(observer.snapshots.get(0).getTraceId()).isNotBlank();
                });
    }

    @Test
    void graphAgentStillStartsWithoutAnyObserverBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KeelAutoConfiguration.class))
                .withBean(ChatModel.class, GraphRagTestFixtures.QueuedChatModel::new)
                .withPropertyValues("keel.agent.mode=graph")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // @Primary 的 guardedGraphKeelAgent 可注入，graphKeelAgent 未被观察者影响
                    assertThat(context).hasBean("graphKeelAgent");
                    assertThat(context).hasBean("guardedGraphKeelAgent");
                    assertThat(context).doesNotHaveBean(StateObserver.class);
                });
    }

    static class ObserverApplication {

        @Bean
        RecordingStateObserver recordingStateObserver() {
            return new RecordingStateObserver();
        }
    }

    /** 记录每步回调的业务侧观察者（FR-14 的典型用法）。 */
    static class RecordingStateObserver implements StateObserver {

        final List<NodeName> nodes = new ArrayList<>();
        final List<StateObserver.Snapshot> snapshots = new ArrayList<>();

        @Override
        public void onStep(NodeName node, StateObserver.Snapshot snapshot) {
            nodes.add(node);
            snapshots.add(snapshot);
        }
    }
}
