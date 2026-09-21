package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;
import io.keel.graph.spi.TracePort;
import io.keel.langfuse.LangfuseTracePort;

/**
 * FR-70 starter 装配测试：
 * ①业务自定义 {@link TracePort} bean 会被 graphKeelAgent 拾取并在请求生命周期内回调；
 * ②keel.langfuse.enabled 默认 false 时，容器内不存在 {@link LangfuseTracePort}
 *   （自动配置不生效，不产生任何外呼）。
 */
@SpringBootTest(properties = "keel.agent.mode=graph")
class GraphLangfuseAutoConfigurationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private GraphRagTestFixtures.QueuedChatModel chatModel;

    @Autowired
    private RecordingTracePort tracePort;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void customTracePortIsWiredIntoGraphAndInvoked() {
        chatModel.reset(
                "{\"action\":\"ANSWER\",\"answer\":\"订单已签收\"}",
                "{\"verdict\":\"APPROVED\"}");

        AgentResult result = keelAgent.run(AgentRequest.builder()
                .principal(new KeelPrincipal("t1", "u1", List.of()))
                .skill("mcp-query")
                .input("查订单")
                .build());

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("订单已签收");
        // Trace 生命周期回调
        assertThat(tracePort.starts).isEqualTo(1);
        assertThat(tracePort.ends).isEqualTo(1);
        assertThat(tracePort.steps)
                .containsExactly(NodeName.PLAN, NodeName.CRITIC, NodeName.GUARD);
    }

    @Test
    void langfuseAutoConfigurationBacksOffWhenDisabled() {
        // 默认 enabled=false：不应创建真实的 LangfuseTracePort（无外呼、无 OTel 资源）
        assertThat(applicationContext.getBeanNamesForType(LangfuseTracePort.class))
                .isEmpty();
    }

    /** 测试用 TracePort：记录回调，不做任何网络上报。 */
    static class RecordingTracePort implements TracePort {

        int starts;
        int ends;
        final java.util.List<NodeName> steps = new java.util.ArrayList<>();

        @Override
        public void onStart(AgentRequest request, SkillDefinition skill) {
            starts++;
        }

        @Override
        public void onStep(NodeName node, LoopState state) {
            steps.add(node);
        }

        @Override
        public void onEnd(AgentResult result) {
            ends++;
        }
    }

    @SpringBootApplication
    static class LangfuseTestApplication {

        @Bean
        ChatModel chatModel() {
            return new GraphRagTestFixtures.QueuedChatModel();
        }

        /** 业务自定义 TracePort，覆盖默认（默认也未启用） */
        @Bean
        TracePort recordingTracePort() {
            return new RecordingTracePort();
        }
    }
}
