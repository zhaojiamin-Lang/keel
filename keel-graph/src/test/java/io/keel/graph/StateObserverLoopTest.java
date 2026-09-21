package io.keel.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.Citation;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;
import io.keel.graph.spi.ModelPort;
import io.keel.graph.spi.RetrievalPort;
import io.keel.graph.spi.StateObserver;
import io.keel.graph.spi.ToolPort;

/**
 * FR-14：验证 {@link GraphKeelAgent} 每步把只读快照**推送**给 {@link StateObserver}。
 *
 * <p>修复前的缺陷：内核只调用无参 {@code stateObserver.snapshot()} 并丢弃返回值，
 * 观察者拿不到任何状态（投递链路是 no-op）。本测试锁定修复后的契约：</p>
 * <ul>
 *   <li>每个执行节点回调一次，node = 刚执行完的节点；</li>
 *   <li>快照内容是执行后的状态（迭代数 / 引用 / 待确认动作 / 草稿答案）；</li>
 *   <li>观察者抛异常不击穿 Loop（观测是旁路）。</li>
 * </ul>
 */
class StateObserverLoopTest {

    private static final KeelPrincipal PRINCIPAL =
            new KeelPrincipal("tenant-1", "user-1", List.of("r1"));

    @Test
    void everyExecutedNodePushesSnapshotWithPostExecutionState() {
        ScriptedModel model = new ScriptedModel();
        model.plans.add(PlanDecision.retrieve("q"));
        model.plans.add(PlanDecision.answer("根据 wiki 的结论"));

        RetrievalPort retrieval = (query, principal) -> List.of(
                new Citation("chunk-1", "wiki/x.md", "片段"));

        RecordingObserver observer = new RecordingObserver();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .retrievalPort(retrieval)
                .stateObserver(observer)
                .skillResolver(name -> Optional.of(skill()))
                .build();

        AgentResult result = agent.run(request());

        assertEquals(AgentStatus.SUCCESS, result.getStatus());
        // 与 TracePort.onStep 同一节点序列：PLAN -> RETRIEVE -> OBSERVE -> PLAN -> CRITIC -> GUARD
        assertEquals(
                List.of(NodeName.PLAN, NodeName.RETRIEVE, NodeName.OBSERVE,
                        NodeName.PLAN, NodeName.CRITIC, NodeName.GUARD),
                observer.nodes);
        // 快照是「执行后」状态：RETRIEVE 那步的 iteration 已由 PLAN 递增为 1
        assertEquals(1, observer.iterationsAt(NodeName.RETRIEVE));
        // 引用在 RETRIEVE 后可见（不是步前空快照）
        assertEquals(1, observer.citationsAt(NodeName.RETRIEVE));
        // 草稿答案在第二轮 PLAN 后可见
        assertTrue(observer.draftAt(NodeName.CRITIC).contains("根据 wiki 的结论"));
        // 快照带请求与 traceId，便于业务按请求关联
        assertEquals("wiki-qa", observer.firstSkillName());
        assertNotNull(observer.firstTraceId());
        assertFalse(observer.firstTraceId().isBlank());
    }

    @Test
    void snapshotIsReadOnlyCopyNotLiveState() {
        // 快照字段为拷贝：后续步骤追加引用不应改变已发出的快照
        ScriptedModel model = new ScriptedModel();
        model.plans.add(PlanDecision.retrieve("q1"));
        model.plans.add(PlanDecision.answer("答案"));

        RetrievalPort retrieval = (query, principal) -> List.of(
                new Citation("chunk-1", "wiki/x.md", "片段"));

        RecordingObserver observer = new RecordingObserver();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .retrievalPort(retrieval)
                .stateObserver(observer)
                .skillResolver(name -> Optional.of(skill()))
                .build();

        agent.run(request());

        // 第一步 PLA N 的快照引用数必为 0，且不会被后续 RETRIEVE 的引用污染（List.copyOf 拷贝）
        assertEquals(0, observer.citationsAt(NodeName.PLAN));
    }

    @Test
    void observerExceptionDoesNotBreakRequest() {
        ScriptedModel model = new ScriptedModel();
        model.plans.add(PlanDecision.answer("直接回答"));

        StateObserver throwing = (node, snapshot) -> {
            throw new IllegalStateException("观察者故障");
        };

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .stateObserver(throwing)
                .skillResolver(name -> Optional.of(readOnlySkill()))
                .build();

        AgentResult result = agent.run(request());

        // 观测是旁路：观察者抛异常不得击穿 Loop
        assertEquals(AgentStatus.SUCCESS, result.getStatus());
    }

    @Test
    void pendingActionVisibleInSnapshotWhenWriteProposed() {
        ScriptedModel model = new ScriptedModel();
        model.plans.add(PlanDecision.proposeWrite(
                new ToolCall("issue.create", "{\"title\":\"子任务\"}")));
        model.plans.add(PlanDecision.answer("已创建"));

        RecordingObserver observer = new RecordingObserver();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .toolPort(new ToolPort() {
                    @Override
                    public ToolObservation invoke(ToolCall call, KeelPrincipal principal) {
                        return ToolObservation.ok(call.getTool(), "ok");
                    }

                    @Override
                    public boolean isWriteTool(String tool) {
                        return "issue.create".equals(tool);
                    }
                })
                .stateObserver(observer)
                .skillResolver(name -> Optional.of(writableSkill()))
                .build();

        AgentResult result = agent.run(writeRequest());

        // 待确认动作对业务可见（FR-14 明确要求「待确认动作」只读可见）
        assertEquals(AgentStatus.NEEDS_CONFIRM, result.getStatus());
        assertEquals("issue.create", observer.pendingTool());
    }

    @Test
    void defaultSnapshotMethodReturnsEmptySnapshot() {
        // 兼容入口语义：主动拉取只能拿到空快照（LoopState 封闭在 graph 包内）
        StateObserver observer = (node, snapshot) -> { };
        assertNull(observer.snapshot().getRequest());
        assertEquals("", observer.snapshot().getDraftAnswer());
        assertTrue(observer.snapshot().getCitations().isEmpty());
    }

    /** 只读问答请求（配 retrieve/answer 脚本使用，skill 由 resolver 决定） */
    private static AgentRequest request() {
        return AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("wiki-qa")
                .input("查一下")
                .build();
    }

    /** 写请求：FR-32 要求带 idempotencyKey，否则 PlanNode 对写动作 fail-closed */
    private static AgentRequest writeRequest() {
        return AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("帮我建个子任务")
                .idempotencyKey("idem-observer-1")
                .build();
    }

    /** 只读 skill：不强制引用，供纯对话脚本使用 */
    private static SkillDefinition readOnlySkill() {
        return SkillDefinition.builder()
                .name("wiki-qa")
                .tools(List.of("wiki.search"))
                .requireCitation(false)
                .writable(false)
                .build();
    }

    /** 强制引用的只读 skill：供 retrieve 脚本使用 */
    private static SkillDefinition skill() {
        return SkillDefinition.builder()
                .name("wiki-qa")
                .tools(List.of("wiki.search"))
                .requireCitation(true)
                .writable(false)
                .build();
    }

    /** 可写 skill（声明 issue.create），供写动作脚本使用 */
    private static SkillDefinition writableSkill() {
        return SkillDefinition.builder()
                .name("ticket")
                .tools(List.of("issue.create"))
                .requireCitation(false)
                .writable(true)
                .build();
    }

    /** 记录每步回调的 StateObserver。 */
    static final class RecordingObserver implements StateObserver {

        final List<NodeName> nodes = new ArrayList<>();
        final List<StateObserver.Snapshot> snapshots = new ArrayList<>();

        @Override
        public void onStep(NodeName node, StateObserver.Snapshot snapshot) {
            nodes.add(node);
            snapshots.add(snapshot);
        }

        int iterationsAt(NodeName node) {
            return snapshotOf(node).getIteration();
        }

        int citationsAt(NodeName node) {
            return snapshotOf(node).getCitations().size();
        }

        String draftAt(NodeName node) {
            return snapshotOf(node).getDraftAnswer();
        }

        String pendingTool() {
            for (StateObserver.Snapshot snapshot : snapshots) {
                if (snapshot.getPendingAction() != null) {
                    return snapshot.getPendingAction().getTool();
                }
            }
            return null;
        }

        String firstSkillName() {
            return snapshots.get(0).getSkill() == null ? null : snapshots.get(0).getSkill().getName();
        }

        String firstTraceId() {
            return snapshots.get(0).getTraceId();
        }

        private StateObserver.Snapshot snapshotOf(NodeName node) {
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i) == node) {
                    return snapshots.get(i);
                }
            }
            throw new AssertionError("未观察到节点: " + node + "，实际序列 " + nodes);
        }
    }

    /** 按脚本返回决策的模型 stub；无脚本时抛异常模拟节点失败。 */
    static final class ScriptedModel implements ModelPort {

        final Queue<PlanDecision> plans = new ArrayDeque<>();

        @Override
        public PlanDecision plan(LoopState state) {
            PlanDecision decision = plans.poll();
            if (decision == null) {
                throw new IllegalStateException("测试脚本耗尽");
            }
            return decision;
        }

        @Override
        public Verdict critique(LoopState state) {
            return Verdict.approved();
        }
    }
}
