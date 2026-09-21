package io.keel.graph;

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
import io.keel.graph.spi.TracePort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-70：验证 {@link GraphKeelAgent} 在请求生命周期内正确回调 {@link TracePort}：
 * onStart 一次、每个执行节点 onStep 一次、onEnd 一次且拿到最终结果。
 */
class TracePortTest {

    private static final KeelPrincipal PRINCIPAL =
            new KeelPrincipal("tenant-1", "user-1", List.of("r1"));

    @Test
    void traceHooksCoverWholeLifecycleInOrder() {
        ScriptedModel model = new ScriptedModel();
        model.plans.add(PlanDecision.retrieve("q"));
        model.plans.add(PlanDecision.answer("根据 wiki 的结论"));

        RetrievalPort retrieval = (query, principal) -> List.of(
                new Citation("chunk-1", "wiki/x.md", "片段"));

        RecordingTracePort trace = new RecordingTracePort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .retrievalPort(retrieval)
                .tracePort(trace)
                .skillResolver(name -> Optional.of(skill()))
                .build();

        AgentResult result = agent.run(request());

        assertEquals(AgentStatus.SUCCESS, result.getStatus());
        // 生命周期顺序与次数
        assertEquals(1, trace.starts);
        assertEquals(1, trace.ends);
        assertFalse(trace.endedBeforeSteps, "onEnd 必须在所有 onStep 之后");
        // 节点序列：PLAN -> RETRIEVE -> OBSERVE -> PLAN -> CRITIC -> GUARD
        assertEquals(
                List.of(NodeName.PLAN, NodeName.RETRIEVE, NodeName.OBSERVE,
                        NodeName.PLAN, NodeName.CRITIC, NodeName.GUARD),
                trace.steps);
        // onEnd 收到的结果即请求最终结果
        assertEquals(AgentStatus.SUCCESS, trace.finalStatus);
        assertTrue(trace.stepNodesContain(NodeName.RETRIEVE));
    }

    @Test
    void traceOnEndStillCalledWhenRequestFails() {
        ScriptedModel model = new ScriptedModel();
        // 不提供任何 plan 脚本，模型 stub 直接抛异常 → LOOP_NODE_ERROR
        RecordingTracePort trace = new RecordingTracePort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .tracePort(trace)
                .skillResolver(name -> Optional.of(skill()))
                .build();

        AgentResult result = agent.run(request());

        assertEquals(AgentStatus.ERROR, result.getStatus());
        // 异常路径也必须关闭 trace，避免悬挂 span
        assertEquals(1, trace.ends);
        assertEquals(AgentStatus.ERROR, trace.finalStatus);
    }

    private static AgentRequest request() {
        return AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("wiki-qa")
                .input("查一下")
                .build();
    }

    private static SkillDefinition skill() {
        return SkillDefinition.builder()
                .name("wiki-qa")
                .tools(List.of("wiki.search"))
                .requireCitation(true)
                .writable(false)
                .build();
    }

    /** 记录生命周期回调的 TracePort。 */
    static final class RecordingTracePort implements TracePort {

        int starts;
        int ends;
        boolean endedBeforeSteps;
        final List<NodeName> steps = new ArrayList<>();
        AgentStatus finalStatus;

        @Override
        public void onStart(AgentRequest request, SkillDefinition skill) {
            starts++;
        }

        @Override
        public void onStep(NodeName node, LoopState state) {
            if (ends > 0) {
                endedBeforeSteps = true;
            }
            steps.add(node);
        }

        @Override
        public void onEnd(AgentResult result) {
            ends++;
            finalStatus = result == null ? null : result.getStatus();
        }

        boolean stepNodesContain(NodeName node) {
            return steps.contains(node);
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
