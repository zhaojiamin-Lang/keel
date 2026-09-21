package io.keel.graph;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.Citation;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;
import io.keel.graph.spi.GuardPort;
import io.keel.graph.spi.ModelPort;
import io.keel.graph.spi.RetrievalPort;
import io.keel.graph.spi.ToolPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认图 Loop 测试：全部使用手写 stub 端口，不引入 Spring AI，
 * 重点验证 FR-10（默认图路径）、FR-11（步数/超时可解释停止）、FR-13（写默认不执行）。
 */
class GraphKeelAgentLoopTest {

    private static final KeelPrincipal PRINCIPAL =
            new KeelPrincipal("tenant-1", "user-1", List.of("project-1"));

    // ---------- FR-10：Plan -> RAG -> Observe -> Plan -> Critic -> Guard ----------

    @Test
    void readOnlyRetrievalFlowSucceedsWithCitation() {
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.retrieve("这单以前怎么处理"));
        model.plans.add(PlanDecision.answer("根据历史记录，处理方式是……"));

        RetrievalPort retrieval = (query, principal) -> List.of(
                new Citation("chunk-1", "wiki/history.md", "历史处理片段"));

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .retrievalPort(retrieval)
                .skillResolver(name -> Optional.of(readOnlySkill("wiki-qa", List.of("wiki.search"))))
                .build();

        AgentResult result = agent.run(request("wiki-qa", "这单以前怎么处理"));

        assertEquals(AgentStatus.SUCCESS, result.getStatus());
        assertEquals(1, result.getCitations().size());
        assertEquals("chunk-1", result.getCitations().get(0).getChunkId());
        assertTrue(result.getPendingActions().isEmpty());
    }

    @Test
    void readOnlyToolFlowSucceedsAndRecordsObservation() {
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.callTool(new ToolCall("issue.read", "{\"id\":\"ISSUE-1\"}")));
        model.plans.add(PlanDecision.answer("ISSUE-1 当前状态为 OPEN"));

        RecordingToolPort tools = new RecordingToolPort(Set.of());

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .skillResolver(name -> Optional.of(
                        readOnlySkill("assistant", List.of("issue.read"), false)))
                .build();

        AgentResult result = agent.run(request("assistant", "查一下 ISSUE-1"));

        assertEquals(AgentStatus.SUCCESS, result.getStatus());
        assertTrue(tools.invoked.contains("issue.read"));
    }

    // ---------- FR-13：写工具默认不执行，只产 PendingAction ----------

    @Test
    void proposedWriteOnWritableSkillNeedsConfirmAndNeverExecutes() {
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.proposeWrite(
                new ToolCall("issue.create", "{\"title\":\"子任务\"}")));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        AgentResult result = agent.run(request("ticket", "帮我建个子任务"));

        assertEquals(AgentStatus.NEEDS_CONFIRM, result.getStatus());
        assertEquals(1, result.getPendingActions().size());
        assertEquals("issue.create", result.getPendingActions().get(0).getTool());
        assertFalse(result.getPendingActions().get(0).isConfirmed());
        assertTrue(tools.invoked.isEmpty(), "写工具在确认前绝不能被执行");
    }

    @Test
    void writeToolCallMasqueradingAsReadStillBlocked() {
        // 模型用 CALL_TOOL（只读）名义请求写工具，代码必须以 ToolPort 判定为准拦截
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.callTool(
                new ToolCall("issue.create", "{\"title\":\"x\"}")));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        AgentResult result = agent.run(request("ticket", "建单"));

        assertEquals(AgentStatus.NEEDS_CONFIRM, result.getStatus());
        assertEquals(1, result.getPendingActions().size());
        assertTrue(tools.invoked.isEmpty());
    }

    @Test
    void proposedWriteOnReadOnlySkillIsRejectedByCodeRule() {
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.proposeWrite(new ToolCall("issue.create", "{}")));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        SkillDefinition readOnlyButListsWrite = SkillDefinition.builder()
                .name("wiki-qa")
                .tools(List.of("issue.create"))
                .requireCitation(true)
                .writable(false)
                .build();
        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .skillResolver(name -> Optional.of(readOnlyButListsWrite))
                .build();

        AgentResult result = agent.run(request("wiki-qa", "建单"));

        assertEquals(AgentStatus.REJECTED, result.getStatus());
        assertEquals("WRITE_FORBIDDEN_BY_SKILL", result.getErrorCode());
        assertTrue(tools.invoked.isEmpty());
    }

    @Test
    void toolOutsideSkillAllowlistIsRejectedWithoutModelRewrite() {
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.callTool(new ToolCall("issue.delete", "{}")));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.delete"));
        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .skillResolver(name -> Optional.of(readOnlySkill("wiki-qa", List.of("wiki.search"))))
                .build();

        AgentResult result = agent.run(request("wiki-qa", "删掉 ISSUE-1"));

        assertEquals(AgentStatus.REJECTED, result.getStatus());
        assertEquals("TOOL_NOT_PERMITTED", result.getErrorCode());
        assertTrue(tools.invoked.isEmpty());
    }

    // ---------- FR-11：最大步数 / 超时必须停止且原因可解释 ----------

    @Test
    void loopStopsWithExplanationWhenMaxIterationsExceeded() {
        StubModel model = new StubModel();
        // 模型不断要求检索，永远不给答案
        model.plans.add(PlanDecision.retrieve("q1"));
        model.plans.add(PlanDecision.retrieve("q2"));
        model.plans.add(PlanDecision.retrieve("q3"));

        RetrievalPort emptyRetrieval = (query, principal) -> List.of();
        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .retrievalPort(emptyRetrieval)
                .budget(new StepBudget(3, Duration.ofSeconds(5)))
                .skillResolver(name -> Optional.of(readOnlySkill("wiki-qa", List.of("wiki.search"))))
                .build();

        AgentResult result = agent.run(request("wiki-qa", "无限检索"));

        assertEquals(AgentStatus.ERROR, result.getStatus());
        assertEquals("MAX_STEPS_EXCEEDED", result.getErrorCode());
        assertFalse(result.getErrorMessage().isBlank());
    }

    @Test
    void loopStopsWithExplanationWhenTimedOut() {
        StubModel model = new StubModel();
        model.sleepMillis = 50;
        model.plans.add(PlanDecision.retrieve("q1"));

        RetrievalPort retrieval = (query, principal) -> List.of(
                new Citation("chunk-1", "wiki/x.md", "片段"));
        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .retrievalPort(retrieval)
                .budget(new StepBudget(5, Duration.ofMillis(1)))
                .skillResolver(name -> Optional.of(readOnlySkill("wiki-qa", List.of("wiki.search"))))
                .build();

        AgentResult result = agent.run(request("wiki-qa", "慢查询"));

        assertEquals(AgentStatus.ERROR, result.getStatus());
        assertEquals("LOOP_TIMEOUT", result.getErrorCode());
    }

    // ---------- FR-10/FR-43：Critic 打回可重规划；预算尽则降级 UNCERTAIN ----------

    @Test
    void criticRejectionLoopsBackToPlanAndThenSucceeds() {
        // 流程：RETRIEVE 召回 chunk-1 -> 第一次 ANSWER 被模型 Critic 打回（引用对不上）
        //       -> 回 PLAN 第二次 ANSWER -> Critic 缺省放行 -> SUCCESS
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.retrieve("q"));
        model.plans.add(PlanDecision.answer("第一次：引用对不上"));
        model.plans.add(PlanDecision.answer("第二次：引用 chunk-1 的结论"));
        model.verdicts.add(Verdict.rejected("CITATION_MISMATCH", "陈述与引用对不上"));

        RetrievalPort retrieval = (query, principal) -> List.of(
                new Citation("chunk-1", "wiki/x.md", "片段"));
        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .retrievalPort(retrieval)
                .skillResolver(name -> Optional.of(readOnlySkill("wiki-qa", List.of("wiki.search"))))
                .build();

        AgentResult result = agent.run(request("wiki-qa", "带引用问答"));

        assertEquals(AgentStatus.SUCCESS, result.getStatus());
        assertEquals(1, result.getCitations().size());
    }

    @Test
    void missingCitationWithNoBudgetLeftDegradesToUncertain() {
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.answer("我没有检索却敢下结论"));

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .budget(new StepBudget(1, Duration.ofSeconds(5)))
                .skillResolver(name -> Optional.of(readOnlySkill("wiki-qa", List.of("wiki.search"))))
                .build();

        AgentResult result = agent.run(request("wiki-qa", "无依据问题"));

        assertEquals(AgentStatus.UNCERTAIN, result.getStatus());
        assertEquals("MISSING_CITATION", result.getErrorCode());
        assertTrue(result.getText().contains("无法给出确定结论"));
    }

    // ---------- Guard 扩展与装配类错误 ----------

    @Test
    void businessGuardCanRejectAnswer() {
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.answer("今天适合关单"));

        GuardPort frozenGuard = state ->
                Verdict.rejected("RELEASE_FROZEN", "发布冻结窗口内禁止该操作");

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .guardPort(frozenGuard)
                .skillResolver(name -> Optional.of(
                        readOnlySkill("chat", List.of(), false)))
                .build();

        AgentResult result = agent.run(request("chat", "今天能关单吗"));

        assertEquals(AgentStatus.REJECTED, result.getStatus());
        assertEquals("RELEASE_FROZEN", result.getErrorCode());
    }

    @Test
    void unknownSkillReturnsExplainableError() {
        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(new StubModel())
                .skillResolver(name -> Optional.empty())
                .build();

        AgentResult result = agent.run(request("not-exists", "hi"));

        assertEquals(AgentStatus.ERROR, result.getStatus());
        assertEquals("UNKNOWN_SKILL", result.getErrorCode());
    }

    @Test
    void blankInputIsRejected() {
        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(new StubModel())
                .build();

        AgentResult result = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .input("  ")
                .build());

        assertEquals(AgentStatus.ERROR, result.getStatus());
        assertEquals("INVALID_REQUEST", result.getErrorCode());
    }

    // ---------- 测试夹具 ----------

    private static AgentRequest request(String skill, String input) {
        // FR-32 P0：默认带幂等键，写动作用例无需每个都显式构造；
        // 缺键场景由 WriteConfirmLoopTest.writeWithoutIdempotencyKeyIsRejected 专门覆盖
        return AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill(skill)
                .input(input)
                .idempotencyKey("idem-" + skill + "-" + System.nanoTime())
                .build();
    }

    private static SkillDefinition readOnlySkill(String name, List<String> tools) {
        return readOnlySkill(name, tools, true);
    }

    private static SkillDefinition readOnlySkill(String name, List<String> tools, boolean requireCitation) {
        return SkillDefinition.builder()
                .name(name)
                .tools(tools)
                .requireCitation(requireCitation)
                .writable(false)
                .build();
    }

    private static SkillDefinition writableSkill(String name, List<String> tools) {
        return SkillDefinition.builder()
                .name(name)
                .tools(tools)
                .requireCitation(false)
                .writable(true)
                .build();
    }

    /** 按脚本返回决策/裁决的模型 stub；sleepMillis 用于模拟慢模型触发超时。 */
    static final class StubModel implements ModelPort {

        final Queue<PlanDecision> plans = new ArrayDeque<>();
        final Queue<Verdict> verdicts = new ArrayDeque<>();
        long sleepMillis;

        @Override
        public PlanDecision plan(LoopState state) {
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            PlanDecision decision = plans.poll();
            if (decision == null) {
                throw new IllegalStateException("测试未给 stub 模型提供更多 plan 决策");
            }
            return decision;
        }

        @Override
        public Verdict critique(LoopState state) {
            Verdict verdict = verdicts.poll();
            return verdict == null ? Verdict.approved() : verdict;
        }
    }

    /** 记录被实际调用的工具；写工具身份由构造参数显式给出。 */
    static final class RecordingToolPort implements ToolPort {

        final Set<String> invoked = new LinkedHashSet<>();
        private final Set<String> writeTools;

        RecordingToolPort(Set<String> writeTools) {
            this.writeTools = writeTools;
        }

        @Override
        public ToolObservation invoke(ToolCall call, KeelPrincipal principal) {
            invoked.add(call.getTool());
            return ToolObservation.ok(call.getTool(), "result-of-" + call.getTool());
        }

        @Override
        public boolean isWriteTool(String tool) {
            return writeTools.contains(tool);
        }
    }
}
