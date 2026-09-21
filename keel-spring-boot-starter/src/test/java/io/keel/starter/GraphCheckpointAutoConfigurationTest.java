package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import io.keel.graph.Checkpoint;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;
import io.keel.graph.StepBudget;
import io.keel.graph.ToolObservation;
import io.keel.graph.spi.CheckpointPort;
import io.keel.graph.spi.ToolPort;

/**
 * Checkpoint 恢复端到端测试（FR-12）：
 * 预存一个「TOOL 已执行、nextNode=PLAN」的 checkpoint，模拟进程重启后用同 sessionId
 * 再次 run，验证 ①从 PLAN 恢复继续 ②工具不被重复调用 ③最终 SUCCESS。
 */
@SpringBootTest(properties = "keel.agent.mode=graph")
class GraphCheckpointAutoConfigurationTest {

    private static final String SESSION_ID = "sess-recover-1";
    private static final String TENANT = "tenant";
    private static final String SUBJECT = "subject";
    /** 与 GraphKeelAgent 内部 runId 生成逻辑一致 */
    private static final String RUN_ID =
            "keel:checkpoint:" + TENANT + ":" + SUBJECT + ":" + SESSION_ID;

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private GraphRagTestFixtures.QueuedChatModel chatModel;

    @Autowired
    private ToolPort toolPort;

    @Autowired
    private InMemoryCheckpointPort checkpointPort;

    @Test
    void resumeFromCheckpointDoesNotReinvokeTool() {
        // 预存 checkpoint：TOOL 已执行（有 observation），下一步回到 PLAN
        SkillDefinition skill = SkillDefinition.builder()
                .name("mcp-query")
                .tools(List.of("search_orders"))
                .build();
        AgentRequest request = request("mcp-query", "查订单");
        LoopState state = new LoopState(request, skill, StepBudget.defaults());
        state.addObservation(ToolObservation.ok("search_orders", "订单已发货"));
        Checkpoint saved = state.toCheckpoint(RUN_ID, NodeName.PLAN);
        checkpointPort.store.put(RUN_ID, saved);

        // 恢复后模型直接 ANSWER（不再 CALL_TOOL）
        chatModel.reset(
                "{\"action\":\"ANSWER\",\"answer\":\"订单已发货\"}",
                "{\"verdict\":\"APPROVED\"}");

        AgentResult result = keelAgent.run(request);

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("订单已发货");
        // 核心断言：恢复后工具没有被重复调用
        verify(toolPort, never()).invoke(any(), any());
    }

    private AgentRequest request(String skill, String input) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal(TENANT, SUBJECT, List.of()))
                .skill(skill)
                .input(input)
                .sessionId(SESSION_ID)
                .build();
    }

    /** 内存版 CheckpointPort：测试用，替代 Redis。 */
    static class InMemoryCheckpointPort implements CheckpointPort {
        final Map<String, Checkpoint> store = new HashMap<>();

        @Override
        public void save(Checkpoint checkpoint) {
            store.put(checkpoint.getRunId(), checkpoint);
        }

        @Override
        public Optional<Checkpoint> load(String runId) {
            return Optional.ofNullable(store.get(runId));
        }

        @Override
        public void delete(String runId) {
            store.remove(runId);
        }
    }

    @SpringBootApplication
    static class CheckpointTestApplication {

        @Bean
        ChatModel chatModel() {
            return new GraphRagTestFixtures.QueuedChatModel();
        }

        @Bean
        ToolPort toolPort() {
            return Mockito.mock(ToolPort.class);
        }

        /** 业务自定义 CheckpointPort 覆盖默认 Redis 实现 */
        @Bean
        CheckpointPort checkpointPort() {
            return new InMemoryCheckpointPort();
        }
    }
}
