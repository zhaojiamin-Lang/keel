package io.keel.model.graph;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import io.keel.core.AgentRequest;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;
import io.keel.graph.GraphExecutionException;
import io.keel.graph.LoopState;
import io.keel.graph.PlanDecision;
import io.keel.graph.StepBudget;
import io.keel.graph.Verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpringAiModelPort} 的结构化输入输出测试：
 * 验证四种规划动作、Critic 裁决的解析，以及非法输出的安全侧处理。
 */
class SpringAiModelPortTest {

    @Test
    void planAnswerIsParsed() {
        ScriptedChatModel chatModel = new ScriptedChatModel(
                "{\"action\":\"ANSWER\",\"answer\":\"pong\"}");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        PlanDecision decision = port.plan(newState());

        assertEquals(PlanDecision.Kind.ANSWER, decision.getKind());
        assertEquals("pong", decision.getText());
        assertTrue(chatModel.lastPrompt.contains(SpringAiModelPort.PLANNER_MARKER));
        assertTrue(chatModel.lastPrompt.contains("wiki-qa"));
    }

    @Test
    void planRetrieveIsParsed() {
        ScriptedChatModel chatModel = new ScriptedChatModel(
                "{\"action\":\"RETRIEVE\",\"query\":\"历史处理方式\"}");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        PlanDecision decision = port.plan(newState());

        assertEquals(PlanDecision.Kind.RETRIEVE, decision.getKind());
        assertEquals("历史处理方式", decision.getText());
    }

    @Test
    void planCallToolSerializesArgumentsObject() {
        ScriptedChatModel chatModel = new ScriptedChatModel(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"issue.read\","
                        + "\"arguments\":{\"id\":\"ISSUE-1\"}}");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        PlanDecision decision = port.plan(newState());

        assertEquals(PlanDecision.Kind.CALL_TOOL, decision.getKind());
        assertEquals("issue.read", decision.getToolCall().getTool());
        assertEquals("{\"id\":\"ISSUE-1\"}", decision.getToolCall().getArgumentsJson());
    }

    @Test
    void planProposeWriteIsParsed() {
        ScriptedChatModel chatModel = new ScriptedChatModel(
                "{\"action\":\"PROPOSE_WRITE\",\"tool\":\"issue.create\","
                        + "\"arguments\":{\"title\":\"子任务\"}}");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        PlanDecision decision = port.plan(newState());

        assertEquals(PlanDecision.Kind.PROPOSE_WRITE, decision.getKind());
        assertEquals("issue.create", decision.getToolCall().getTool());
    }

    @Test
    void planAcceptsJsonWrappedInCodeFence() {
        ScriptedChatModel chatModel = new ScriptedChatModel(
                "```json\n{\"action\":\"ANSWER\",\"answer\":\"ok\"}\n```");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        PlanDecision decision = port.plan(newState());

        assertEquals(PlanDecision.Kind.ANSWER, decision.getKind());
        assertEquals("ok", decision.getText());
    }

    @Test
    void planMissingArgumentsYieldsEmptyArgumentsJson() {
        ScriptedChatModel chatModel = new ScriptedChatModel(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"issue.ping\"}");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        PlanDecision decision = port.plan(newState());

        assertEquals("", decision.getToolCall().getArgumentsJson());
    }

    @Test
    void invalidPlanJsonRaisesModelOutputInvalid() {
        ScriptedChatModel chatModel = new ScriptedChatModel("我觉得直接告诉你吧：没问题");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        GraphExecutionException exception = assertThrows(
                GraphExecutionException.class, () -> port.plan(newState()));
        assertEquals("MODEL_OUTPUT_INVALID", exception.getCode());
    }

    @Test
    void unknownPlanActionRaisesModelOutputInvalid() {
        ScriptedChatModel chatModel = new ScriptedChatModel(
                "{\"action\":\"SING\",\"lyrics\":\"...\"}");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        GraphExecutionException exception = assertThrows(
                GraphExecutionException.class, () -> port.plan(newState()));
        assertEquals("MODEL_OUTPUT_INVALID", exception.getCode());
    }

    @Test
    void modelFailureRaisesModelCallFailed() {
        ScriptedChatModel chatModel = new ScriptedChatModel();
        chatModel.failWith = new RuntimeException("connection reset");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        GraphExecutionException exception = assertThrows(
                GraphExecutionException.class, () -> port.plan(newState()));
        assertEquals("MODEL_CALL_FAILED", exception.getCode());
    }

    @Test
    void critiqueApprovedIsParsed() {
        ScriptedChatModel chatModel = new ScriptedChatModel("{\"verdict\":\"APPROVED\"}");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        Verdict verdict = port.critique(newState());

        assertTrue(verdict.isApproved());
        assertTrue(chatModel.lastPrompt.contains(SpringAiModelPort.CRITIC_MARKER));
    }

    @Test
    void critiqueRejectedIsParsed() {
        ScriptedChatModel chatModel = new ScriptedChatModel(
                "{\"verdict\":\"REJECTED\",\"code\":\"CITATION_MISMATCH\","
                        + "\"reason\":\"结论在片段中没有依据\"}");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        Verdict verdict = port.critique(newState());

        assertFalse(verdict.isApproved());
        assertEquals("CITATION_MISMATCH", verdict.getCode());
        assertEquals("结论在片段中没有依据", verdict.getReason());
    }

    @Test
    void invalidCritiqueJsonIsTreatedAsRejection() {
        ScriptedChatModel chatModel = new ScriptedChatModel("我觉得还行吧");
        SpringAiModelPort port = new SpringAiModelPort(chatModel);

        Verdict verdict = port.critique(newState());

        assertFalse(verdict.isApproved());
        assertEquals("CRITIC_OUTPUT_INVALID", verdict.getCode());
    }

    private static LoopState newState() {
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("tenant-1", "user-1", List.of("project-1")))
                .skill("wiki-qa")
                .input("这单以前怎么处理")
                .build();
        SkillDefinition skill = SkillDefinition.builder()
                .name("wiki-qa")
                .description("Wiki QA")
                .tools(List.of("wiki.search"))
                .requireCitation(true)
                .build();
        return new LoopState(request, skill, StepBudget.defaults());
    }

    /** 按脚本依次返回固定文本的 ChatModel；可记录最后一次 Prompt 文本或模拟调用失败。 */
    static final class ScriptedChatModel implements ChatModel {

        final Queue<String> replies = new ArrayDeque<>();
        String lastPrompt = "";
        RuntimeException failWith;

        ScriptedChatModel(String... replies) {
            this.replies.addAll(List.of(replies));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt = prompt.getInstructions().stream()
                    .map(message -> message.getContent())
                    .reduce("", (a, b) -> a + "\n" + b);
            if (failWith != null) {
                throw failWith;
            }
            String reply = replies.poll();
            if (reply == null) {
                throw new IllegalStateException("测试未提供更多模型回复");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }
}
