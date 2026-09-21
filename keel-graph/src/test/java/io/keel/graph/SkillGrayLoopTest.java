package io.keel.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;
import io.keel.graph.spi.ModelPort;
import io.keel.graph.spi.TracePort;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Skill 灰度回路测试（§8 第三期）：
 * <ol>
 *     <li>灰度策略返回灰度版本时，显式指定 skill 的请求命中灰度定义；</li>
 *     <li>策略返回 empty 时沿用稳定版本；</li>
 *     <li>灰度在 trace 启动前收口——Langfuse 上报的是实际命中的版本（FR-70）；</li>
 *     <li>不配置灰度策略 = 现行为零变化。</li>
 * </ol>
 */
class SkillGrayLoopTest {

    private static final KeelPrincipal PRINCIPAL =
            new KeelPrincipal("tenant-1", "user-1", List.of());

    private static SkillDefinition skill(String name, String version) {
        return SkillDefinition.builder()
                .name(name)
                .description("desc of " + name)
                .version(version)
                .build();
    }

    @Test
    void grayPolicyReplacesStableSkillOnExplicitPath() {
        SkillDefinition stable = skill("ticket", "1.0");
        SkillDefinition gray = skill("ticket", "2.0");

        StubModel model = new StubModel();
        model.plans.add(PlanDecision.answer("灰度版本的回答"));
        RecordingTracePort trace = new RecordingTracePort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .tracePort(trace)
                .skillResolver(name -> Optional.of(stable))
                .skillGrayPolicy((request, s) -> Optional.of(gray))
                .build();

        AgentResult result = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("工单相关问题")
                .build());

        assertEquals(AgentStatus.SUCCESS, result.getStatus());
        // 灰度收口在 trace 之前：观测系统看到的必须是实际命中的版本（FR-70）
        assertEquals(gray, trace.startedSkill,
                "trace.onStart 必须拿到灰度命中后的版本");
    }

    @Test
    void emptyPolicyDecisionKeepsStableVersion() {
        SkillDefinition stable = skill("ticket", "1.0");

        StubModel model = new StubModel();
        model.plans.add(PlanDecision.answer("稳定版本的回答"));
        RecordingTracePort trace = new RecordingTracePort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .tracePort(trace)
                .skillResolver(name -> Optional.of(stable))
                // 策略返回 empty = 沿用稳定版（如 percent 未命中）
                .skillGrayPolicy((request, s) -> Optional.empty())
                .build();

        AgentResult result = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("工单相关问题")
                .build());

        assertEquals(AgentStatus.SUCCESS, result.getStatus());
        assertEquals(stable, trace.startedSkill, "策略未命中时应保持稳定版本");
    }

    @Test
    void noGrayPolicyConfiguredMeansStableAlways() {
        SkillDefinition stable = skill("ticket", "1.0");

        StubModel model = new StubModel();
        model.plans.add(PlanDecision.answer("稳定版本的回答"));
        RecordingTracePort trace = new RecordingTracePort();

        GraphKeelAgent agent = GraphKeelAgent.builder()
                .modelPort(model)
                .tracePort(trace)
                .skillResolver(name -> Optional.of(stable))
                .build();

        AgentResult result = agent.run(AgentRequest.builder()
                .principal(PRINCIPAL)
                .skill("ticket")
                .input("工单相关问题")
                .build());

        assertEquals(AgentStatus.SUCCESS, result.getStatus());
        assertEquals(stable, trace.startedSkill, "不配置灰度策略时行为零变化");
    }

    // ---------- 测试夹具 ----------

    /** 与 WriteConfirmLoopTest.StubModel 等价的内存模型桩（答复型回路）。 */
    static final class StubModel implements ModelPort {

        final java.util.Queue<PlanDecision> plans = new java.util.ArrayDeque<>();

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
            return Verdict.approved();
        }
    }

    /** 记录 onStart 收到的 skill，验证灰度命中版本进入观测链路。 */
    static final class RecordingTracePort implements TracePort {

        final List<SkillDefinition> startedSkills = new ArrayList<>();
        SkillDefinition startedSkill;

        @Override
        public void onStart(AgentRequest request, SkillDefinition skill) {
            this.startedSkill = skill;
            this.startedSkills.add(skill);
        }

        @Override
        public void onStep(NodeName node, LoopState state) {
            // 测试不关心单步
        }

        @Override
        public void onEnd(AgentResult result) {
            // 测试不关心结束
        }
    }
}
