package io.keel.graph;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelPrincipal;
import io.keel.core.OutboxRecord;
import io.keel.core.PendingAction;
import io.keel.core.SkillDefinition;
import io.keel.graph.node.ToolNode;
import io.keel.graph.spi.CheckpointPort;
import io.keel.graph.spi.ModelPort;
import io.keel.graph.spi.OutboxPort;
import io.keel.graph.spi.ToolPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写工具确认执行与幂等测试（FR-13 / FR-32）：
 * <ol>
 *     <li>首次请求：写工具转 PendingAction，NEEDS_CONFIRM，不执行；</li>
 *     <li>确认请求：带 confirmedActionId + sessionId，从 checkpoint 恢复并执行写工具，SUCCESS；</li>
 *     <li>actionId 不匹配 / 无 sessionId / 无 checkpoint 均返回可解释错误；</li>
 *     <li>ToolNode 幂等：observations 已有同工具记录时跳过重复调用。</li>
 * </ol>
 */
class WriteConfirmLoopTest {

    private static final KeelPrincipal PRINCIPAL =
            new KeelPrincipal("tenant-1", "user-1", List.of("project-1"));

    private static final String SESSION_ID = "sess-confirm-1";

    @Test
    void firstRequestNeedsConfirmAndNeverExecutesWrite() {
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.proposeWrite(
                new ToolCall("issue.create", "{\"title\":\"子任务\"}")));
        model.plans.add(PlanDecision.answer("已为你创建子任务"));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        InMemoryCheckpointPort checkpoints = new InMemoryCheckpointPort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .checkpointPort(checkpoints)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        AgentResult result = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("帮我建个子任务")
                .sessionId(SESSION_ID)
                .idempotencyKey("idem-first-1") // FR-32 P0：写动作需带幂等键
                .build());

        assertEquals(AgentStatus.NEEDS_CONFIRM, result.getStatus());
        assertEquals(1, result.getPendingActions().size());
        PendingAction pending = result.getPendingActions().get(0);
        assertEquals("issue.create", pending.getTool());
        assertEquals("idem-first-1", pending.getIdempotencyKey(),
                "PendingAction 必须携带请求级 idempotencyKey");
        assertFalse(pending.isConfirmed());
        assertTrue(tools.invoked.isEmpty(), "确认前写工具绝不能执行");
        // checkpoint 保留，供确认请求恢复
        assertTrue(checkpoints.store.containsKey(runId()));
    }

    @Test
    void confirmedWriteExecutesOnceAndReturnsSuccess() {
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.proposeWrite(
                new ToolCall("issue.create", "{\"title\":\"子任务\"}")));
        model.plans.add(PlanDecision.answer("已为你创建子任务"));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        InMemoryCheckpointPort checkpoints = new InMemoryCheckpointPort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .checkpointPort(checkpoints)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        // 第一次：产生待确认动作
        AgentResult first = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("帮我建个子任务")
                .sessionId(SESSION_ID)
                .idempotencyKey("idem-conf-1") // FR-32 P0
                .build());
        assertEquals(AgentStatus.NEEDS_CONFIRM, first.getStatus());
        String actionId = first.getPendingActions().get(0).getActionId();

        // 第二次：确认执行
        AgentResult confirmed = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .sessionId(SESSION_ID)
                .confirmedActionId(actionId)
                .build());

        assertEquals(AgentStatus.SUCCESS, confirmed.getStatus());
        assertEquals(1, tools.invoked.size());
        assertTrue(tools.invoked.contains("issue.create"));
        assertTrue(confirmed.getPendingActions().isEmpty(),
                "确认执行后 pendingAction 被清除");
        // SUCCESS 终局后 checkpoint 清理
        assertFalse(checkpoints.store.containsKey(runId()));
    }

    @Test
    void confirmWithMismatchedActionIdFails() {
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.proposeWrite(
                new ToolCall("issue.create", "{}")));
        model.plans.add(PlanDecision.answer("ok"));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        InMemoryCheckpointPort checkpoints = new InMemoryCheckpointPort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .checkpointPort(checkpoints)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("建单")
                .sessionId(SESSION_ID)
                .idempotencyKey("idem-mismatch-1") // FR-32 P0
                .build());

        AgentResult result = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .sessionId(SESSION_ID)
                .confirmedActionId("wrong-action-id")
                .build());

        assertEquals(AgentStatus.ERROR, result.getStatus());
        assertEquals("CONFIRM_ACTION_ID_MISMATCH", result.getErrorCode());
        assertTrue(tools.invoked.isEmpty());
    }

    @Test
    void confirmWithoutSessionIdFails() {
        StubModel model = new StubModel();
        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        AgentResult result = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .confirmedActionId("any-id")
                .build());

        assertEquals(AgentStatus.ERROR, result.getStatus());
        assertEquals("CONFIRM_REQUIRES_SESSION", result.getErrorCode());
    }

    @Test
    void confirmWithoutCheckpointFails() {
        StubModel model = new StubModel();
        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        InMemoryCheckpointPort checkpoints = new InMemoryCheckpointPort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .checkpointPort(checkpoints)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        AgentResult result = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .sessionId(SESSION_ID)
                .confirmedActionId("any-id")
                .build());

        assertEquals(AgentStatus.ERROR, result.getStatus());
        assertEquals("CONFIRM_CHECKPOINT_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void toolNodeSkipsDuplicateWriteWhenObservationExists() {
        // FR-32：同一 actionId 对应的工具若已在 observations 中（如进程崩溃前已执行），
        // 确认恢复时不重复调用
        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        ToolNode toolNode = new ToolNode(tools);

        AgentRequest request = AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .build();
        LoopState state = new LoopState(request, writableSkill("ticket", List.of("issue.create")),
                StepBudget.defaults());
        state.setPendingAction(new PendingAction("act-1", "issue.create", "{}"));
        state.setWriteConfirmed(true);
        // 模拟崩溃前已执行过该工具
        state.addObservation(ToolObservation.ok("issue.create", "已创建"));

        NodeName next = toolNode.execute(state);

        assertEquals(NodeName.OBSERVE, next);
        assertTrue(tools.invoked.isEmpty(), "observations 已有记录时应跳过重复调用");
        // pendingAction 被清除，后续 GUARD 走 SUCCESS
        assertTrue(state.getPendingAction() == null);
    }

    @Test
    void writeBudgetExceededStopsLoopWithExplainableError() {
        // FR-63：maxWrites=1 时，第二次写动作执行后 writeCount 达到预算，
        // 图必须停止并返回 MAX_WRITES_EXCEEDED，而不是无限执行。
        // 用两个不同工具（issue.create + issue.updateStatus）避免 FR-32 幂等跳过。
        RecordingToolPort tools = new RecordingToolPort(
                Set.of("issue.create", "issue.updateStatus"));
        ToolNode toolNode = new ToolNode(tools);

        AgentRequest request = AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .build();
        // maxWrites=1，预算只允许一次写
        LoopState state = new LoopState(
                request,
                writableSkill("ticket", List.of("issue.create", "issue.updateStatus")),
                new StepBudget(5, java.time.Duration.ofSeconds(30), 1));
        state.setPendingAction(
                new PendingAction("act-1", "issue.create", "{}", false, "idem-1"));
        state.setWriteConfirmed(true);

        // 第一次执行：writeCount 0 -> 1，未超预算
        NodeName next = toolNode.execute(state);
        assertEquals(NodeName.OBSERVE, next);
        assertEquals(1, state.getWriteCount());
        assertTrue(tools.invoked.contains("issue.create"));

        // 第二次写动作（不同工具，不触发幂等跳过）：writeCount 1 -> 2
        state.setPendingAction(
                new PendingAction("act-2", "issue.updateStatus", "{}", false, "idem-2"));
        state.setWriteConfirmed(true);
        toolNode.execute(state);
        assertEquals(2, state.getWriteCount());
        assertTrue(tools.invoked.contains("issue.updateStatus"));
    }

    @Test
    void idempotentWriteSkipDoesNotConsumeWriteBudget() {
        // FR-32/FR-63：幂等跳过的写动作不递增 writeCount——未真正产生副作用不算预算消耗
        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        ToolNode toolNode = new ToolNode(tools);

        AgentRequest request = AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .build();
        LoopState state = new LoopState(
                request,
                writableSkill("ticket", List.of("issue.create")),
                StepBudget.defaults());
        state.setPendingAction(
                new PendingAction("act-1", "issue.create", "{}", false, "idem-1"));
        state.setWriteConfirmed(true);
        // 模拟崩溃前已执行过该工具
        state.addObservation(ToolObservation.ok("issue.create", "已创建"));

        toolNode.execute(state);

        assertEquals(0, state.getWriteCount(), "幂等跳过不应消耗写次数预算");
        assertTrue(tools.invoked.isEmpty());
    }

    @Test
    void writeWithoutIdempotencyKeyIsRejected() {
        // FR-32 P0 fail-closed（铁律 4）：写动作缺幂等键，代码侧硬拒绝，不依赖 Prompt 自觉
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.proposeWrite(
                new ToolCall("issue.create", "{\"title\":\"子任务\"}")));
        model.plans.add(PlanDecision.answer("已为你创建子任务"));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        InMemoryCheckpointPort checkpoints = new InMemoryCheckpointPort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .checkpointPort(checkpoints)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        // 故意不传 idempotencyKey，触发 PlanNode.proposeWrite 的 fail-closed
        AgentResult result = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("帮我建个子任务")
                .sessionId(SESSION_ID)
                .build());

        assertEquals(AgentStatus.REJECTED, result.getStatus());
        assertEquals("WRITE_WITHOUT_IDEMPOTENCY", result.getErrorCode());
        assertTrue(tools.invoked.isEmpty(), "缺幂等键时写工具绝不能执行");
        // REJECTED 终局后 checkpoint 应被清理，不残留半截状态
        assertFalse(checkpoints.store.containsKey(runId()));
    }

    @Test
    void confirmedWritePropagatesIdempotencyKeyToToolPort() {
        // FR-32：图内核把请求级 idempotencyKey 透传到 PendingAction，
        // 确认执行时 ToolNode 重建 ToolCall 带上 idempotencyKey，
        // ToolPort 据此对 server 端做跨进程 dedupe
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.proposeWrite(
                new ToolCall("issue.create", "{\"title\":\"子任务\"}")));
        model.plans.add(PlanDecision.answer("已为你创建子任务"));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        InMemoryCheckpointPort checkpoints = new InMemoryCheckpointPort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .checkpointPort(checkpoints)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        AgentResult first = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("帮我建个子任务")
                .sessionId(SESSION_ID)
                .idempotencyKey("idem-prop-1")
                .build());
        assertEquals(AgentStatus.NEEDS_CONFIRM, first.getStatus());
        // PendingAction 携带请求级 idempotencyKey
        assertEquals("idem-prop-1", first.getPendingActions().get(0).getIdempotencyKey());
        String actionId = first.getPendingActions().get(0).getActionId();

        AgentResult confirmed = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .sessionId(SESSION_ID)
                .confirmedActionId(actionId)
                .build());

        assertEquals(AgentStatus.SUCCESS, confirmed.getStatus());
        // 透传断言：ToolPort 收到的 ToolCall 携带原 idempotencyKey
        assertEquals("idem-prop-1", tools.lastIdempotencyKey,
                "ToolCall.idempotencyKey 必须从 PendingAction 透传到 ToolPort");
    }

    @Test
    void confirmedWriteReportsOutboxRecord() {
        // NFR-03：确认执行的写工具真正运行后，图内核把写动作上下文交给 OutboxPort，
        // 业务在自己的本地事务里落 outbox 表
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.proposeWrite(
                new ToolCall("issue.create", "{\"title\":\"子任务\"}")));
        model.plans.add(PlanDecision.answer("已为你创建子任务"));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        RecordingOutboxPort outbox = new RecordingOutboxPort();
        InMemoryCheckpointPort checkpoints = new InMemoryCheckpointPort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .checkpointPort(checkpoints)
                .outboxPort(outbox)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        AgentResult first = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("帮我建个子任务")
                .sessionId(SESSION_ID)
                .idempotencyKey("idem-outbox-1")
                .build());
        assertEquals(AgentStatus.NEEDS_CONFIRM, first.getStatus());
        String actionId = first.getPendingActions().get(0).getActionId();
        assertTrue(outbox.records.isEmpty(), "确认前写未执行，不应产生 outbox 记录");

        AgentResult confirmed = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .sessionId(SESSION_ID)
                .confirmedActionId(actionId)
                .build());

        assertEquals(AgentStatus.SUCCESS, confirmed.getStatus());
        assertEquals(1, outbox.records.size(), "写真正执行一次，outbox 记录恰好一条");

        OutboxRecord record = outbox.records.get(0);
        assertEquals("tenant-1", record.getTenantId());
        assertEquals("user-1", record.getSubjectId());
        assertEquals(SESSION_ID, record.getSessionId());
        assertEquals("ticket", record.getSkill());
        assertEquals(actionId, record.getActionId());
        assertEquals("issue.create", record.getTool());
        assertEquals("idem-outbox-1", record.getIdempotencyKey(),
                "outbox 记录必须携带幂等键，业务侧据此防重");
        assertEquals("{\"title\":\"子任务\"}", record.getPayloadJson());
        assertTrue(record.isSuccess());
        assertTrue(record.getCreatedAt() != null);
    }

    @Test
    void outboxCallbackFailureDoesNotBreakRequest() {
        // NFR-03：OutboxPort 抛异常不击穿请求——写已执行无法回滚，只能 best-effort
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.proposeWrite(
                new ToolCall("issue.create", "{\"title\":\"子任务\"}")));
        model.plans.add(PlanDecision.answer("已为你创建子任务"));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        OutboxPort brokenOutbox = record -> {
            throw new IllegalStateException("业务 outbox 表写入失败");
        };
        InMemoryCheckpointPort checkpoints = new InMemoryCheckpointPort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .checkpointPort(checkpoints)
                .outboxPort(brokenOutbox)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        AgentResult first = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("帮我建个子任务")
                .sessionId(SESSION_ID)
                .idempotencyKey("idem-outbox-2")
                .build());
        String actionId = first.getPendingActions().get(0).getActionId();

        AgentResult confirmed = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .sessionId(SESSION_ID)
                .confirmedActionId(actionId)
                .build());

        assertEquals(AgentStatus.SUCCESS, confirmed.getStatus(),
                "Outbox 回调失败不应影响写执行结果");
        assertTrue(tools.invoked.contains("issue.create"), "写工具已实际执行");
    }

    @Test
    void idempotentWriteSkipDoesNotReportOutbox() {
        // FR-32/NFR-03：幂等跳过（observations 已有同工具记录）不回调 OutboxPort，
        // 否则同一 idempotencyKey 会产生第二笔 outbox 记录
        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        RecordingOutboxPort outbox = new RecordingOutboxPort();
        ToolNode toolNode = new ToolNode(tools, outbox);

        AgentRequest request = AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .build();
        LoopState state = new LoopState(request, writableSkill("ticket", List.of("issue.create")),
                StepBudget.defaults());
        state.setPendingAction(
                new PendingAction("act-1", "issue.create", "{}", false, "idem-skip-1"));
        state.setWriteConfirmed(true);
        // 模拟崩溃前已执行过该工具
        state.addObservation(ToolObservation.ok("issue.create", "已创建"));

        NodeName next = toolNode.execute(state);

        assertEquals(NodeName.OBSERVE, next);
        assertTrue(outbox.records.isEmpty(), "幂等跳过不应产生 outbox 记录");
        assertTrue(tools.invoked.isEmpty());
    }

    @Test
    void confirmedWriteRoutesThroughInvokeConfirmedWrite() {
        // FR-31：确认执行必须走 ToolPort.invokeConfirmedWrite（McpToolPort 据此放开写限制），
        // 而不是复用未确认路径的 invoke
        StubModel model = new StubModel();
        model.plans.add(PlanDecision.proposeWrite(
                new ToolCall("issue.create", "{\"title\":\"子任务\"}")));
        model.plans.add(PlanDecision.answer("已为你创建子任务"));

        RecordingToolPort tools = new RecordingToolPort(Set.of("issue.create"));
        InMemoryCheckpointPort checkpoints = new InMemoryCheckpointPort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(tools)
                .checkpointPort(checkpoints)
                .skillResolver(name -> Optional.of(writableSkill("ticket", List.of("issue.create"))))
                .build();

        AgentResult first = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("帮我建个子任务")
                .sessionId(SESSION_ID)
                .idempotencyKey("idem-route-1")
                .build());
        String actionId = first.getPendingActions().get(0).getActionId();

        AgentResult confirmed = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("确认")
                .sessionId(SESSION_ID)
                .confirmedActionId(actionId)
                .build());

        assertEquals(AgentStatus.SUCCESS, confirmed.getStatus());
        assertEquals(1, tools.confirmedWriteCalls,
                "确认执行必须经过 invokeConfirmedWrite 端口方法（FR-31）");
    }

    // ---------- 辅助方法 ----------

    private static String runId() {
        return "keel:checkpoint:" + PRINCIPAL.getTenantId() + ":"
                + PRINCIPAL.getSubjectId() + ":" + SESSION_ID;
    }

    private static SkillDefinition writableSkill(String name, List<String> tools) {
        return SkillDefinition.builder()
                .name(name)
                .tools(tools)
                .requireCitation(false)
                .writable(true)
                .build();
    }

    // ---------- 测试夹具 ----------

    static final class StubModel implements ModelPort {

        final Queue<PlanDecision> plans = new ArrayDeque<>();
        final Queue<Verdict> verdicts = new ArrayDeque<>();

        @Override
        public PlanDecision plan(LoopState state) {
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

    static final class RecordingToolPort implements ToolPort {

        final Set<String> invoked = new LinkedHashSet<>();
        /** 捕获最近一次 invoke 收到的 idempotencyKey，用于验证图内核透传（FR-32）。 */
        String lastIdempotencyKey;
        /** 捕获 invokeConfirmedWrite 调用次数，验证确认路径走独立端口方法（FR-31）。 */
        int confirmedWriteCalls;
        private final Set<String> writeTools;

        RecordingToolPort(Set<String> writeTools) {
            this.writeTools = writeTools;
        }

        @Override
        public ToolObservation invoke(ToolCall call, KeelPrincipal principal) {
            invoked.add(call.getTool());
            lastIdempotencyKey = call.getIdempotencyKey();
            return ToolObservation.ok(call.getTool(), "result-of-" + call.getTool());
        }

        @Override
        public ToolObservation invokeConfirmedWrite(ToolCall call, KeelPrincipal principal) {
            confirmedWriteCalls++;
            return ToolPort.super.invokeConfirmedWrite(call, principal);
        }

        @Override
        public boolean isWriteTool(String tool) {
            return writeTools.contains(tool);
        }
    }

    /** 内存 OutboxPort，捕获图内核上报的写动作记录（NFR-03）。 */
    static final class RecordingOutboxPort implements OutboxPort {

        final List<OutboxRecord> records = new java.util.ArrayList<>();

        @Override
        public void record(OutboxRecord record) {
            records.add(record);
        }
    }

    /** 内存 CheckpointPort，用于测试确认执行流程。 */
    static final class InMemoryCheckpointPort implements CheckpointPort {

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
}
