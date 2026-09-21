package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.KeelPrincipal;
import io.keel.model.graph.SpringAiModelPort;

/**
 * keel.agent.mode=graph 下端到端装配测试：验证 GraphKeelAgent 成为 KeelAgent，
 * 以及 FR-13（写默认不执行只产待确认动作）和引用硬规则在真实 Spring 上下文中的行为。
 */
@SpringBootTest(properties = "keel.agent.mode=graph")
class GraphKeelAgentAutoConfigurationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private ScriptedGraphChatModel chatModel;

    @BeforeEach
    void resetModel() {
        chatModel.reset(
                "{\"action\":\"ANSWER\",\"answer\":\"pong\"}",
                "{\"verdict\":\"APPROVED\"}");
    }

    @Test
    void answerFlowReturnsSuccess() {
        AgentResult result = keelAgent.run(request("chat", "ping"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("pong");
        assertThat(chatModel.planCalls).isEqualTo(1);
        assertThat(chatModel.criticCalls).isEqualTo(1);
    }

    @Test
    void proposedWriteOnWritableSkillNeedsConfirmAndIsNotExecuted() {
        chatModel.reset(
                "{\"action\":\"PROPOSE_WRITE\",\"tool\":\"issue.create\","
                        + "\"arguments\":{\"title\":\"子任务\"}}",
                "{\"verdict\":\"APPROVED\"}");

        AgentResult result = keelAgent.run(request("ticket-ops", "帮我建个子任务"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.NEEDS_CONFIRM);
        assertThat(result.getPendingActions()).hasSize(1);
        assertThat(result.getPendingActions().get(0).getTool()).isEqualTo("issue.create");
        assertThat(result.getPendingActions().get(0).isConfirmed()).isFalse();
    }

    @Test
    void proposedWriteOnReadOnlySkillIsRejectedByCodeRule() {
        chatModel.reset(
                "{\"action\":\"PROPOSE_WRITE\",\"tool\":\"issue.create\","
                        + "\"arguments\":{\"title\":\"x\"}}",
                "{\"verdict\":\"APPROVED\"}");

        AgentResult result = keelAgent.run(request("ticket-suggest", "建单"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.REJECTED);
        assertThat(result.getErrorCode()).isEqualTo("WRITE_FORBIDDEN_BY_SKILL");
    }

    @Test
    void requireCitationWithoutEvidenceDegradesToUncertainUsingCodeRuleOnly() {
        // 模型始终直接作答且零召回：Critic 的代码硬规则应打回 5 次后降级，
        // 整个过程不应该调用模型 Critic（安全规则不靠模型，铁律 4）
        chatModel.reset(
                "{\"action\":\"ANSWER\",\"answer\":\"没有检索也敢下结论\"}",
                "{\"verdict\":\"APPROVED\"}");

        AgentResult result = keelAgent.run(request("wiki-qa", "无依据问题"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.UNCERTAIN);
        assertThat(result.getErrorCode()).isEqualTo("MISSING_CITATION");
        assertThat(result.getText()).contains("无法给出确定结论");
        assertThat(chatModel.planCalls).isEqualTo(5);
        assertThat(chatModel.criticCalls).isZero();
    }

    @Test
    void unknownSkillIsRejectedBeforeEnteringGraph() {
        AgentResult result = keelAgent.run(request("not-exist", "hi"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("UNKNOWN_SKILL");
        assertThat(chatModel.planCalls).isZero();
    }

    private AgentRequest request(String skill, String input) {
        // FR-32 P0：默认带幂等键，写动作用例无需显式构造；
        // 缺键 fail-closed 由 GraphKeelAgentLoopTest/WriteConfirmLoopTest 专门覆盖
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .skill(skill)
                .input(input)
                .idempotencyKey("idem-" + skill + "-" + System.nanoTime())
                .build();
    }

    @SpringBootApplication
    static class TestApplication {

        @Bean
        ScriptedGraphChatModel chatModel() {
            return new ScriptedGraphChatModel();
        }
    }

    /** 按提示词标记区分规划/评审调用并返回脚本 JSON 的 ChatModel。 */
    static class ScriptedGraphChatModel implements ChatModel {

        private String planReply;
        private String criticReply;
        private int planCalls;
        private int criticCalls;

        void reset(String planReply, String criticReply) {
            this.planReply = planReply;
            this.criticReply = criticReply;
            this.planCalls = 0;
            this.criticCalls = 0;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String text = prompt.getInstructions().stream()
                    .map(message -> message.getContent())
                    .reduce("", (a, b) -> a + "\n" + b);
            String reply;
            if (text.contains(SpringAiModelPort.CRITIC_MARKER)) {
                criticCalls++;
                reply = criticReply;
            } else {
                planCalls++;
                reply = planReply;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }
}
