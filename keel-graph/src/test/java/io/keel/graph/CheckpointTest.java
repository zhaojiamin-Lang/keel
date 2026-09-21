package io.keel.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.AgentStatus;
import io.keel.core.Citation;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;

/**
 * Checkpoint 序列化与恢复测试（FR-12）：
 * <ol>
 *   <li>LoopState → Checkpoint → fromCheckpoint 字段一致；</li>
 *   <li>Checkpoint 经 Java 原生序列化/反序列化后字段一致（RedisCheckpointPort 的实际路径）；</li>
 *   <li>fromCheckpoint 不沿用旧 deadline，恢复后未超时。</li>
 * </ol>
 */
class CheckpointTest {

    @Test
    void loopStateRoundTripPreservesState() {
        SkillDefinition skill = SkillDefinition.builder()
                .name("wiki-qa")
                .requireCitation(true)
                .tools(List.of("search"))
                .build();
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t1", "u1", List.of("res1")))
                .skill("wiki-qa")
                .input("查一下")
                .sessionId("sess-1")
                .build();

        LoopState original = new LoopState(request, skill, StepBudget.defaults());
        original.incrementIteration();
        original.addCitations(List.of(new Citation("c1", "s1", "snippet1")));
        original.addObservation(ToolObservation.ok("search", "结果"));
        original.setDraftAnswer("草稿");
        original.setLastDecision(PlanDecision.answer("草稿"));
        original.setCriticVerdict(Verdict.approved());
        original.terminate(AgentStatus.SUCCESS, "");

        Checkpoint checkpoint = original.toCheckpoint("run-1", NodeName.GUARD);

        LoopState restored = LoopState.fromCheckpoint(checkpoint);

        assertThat(restored.getTraceId()).isEqualTo(original.getTraceId());
        assertThat(restored.getIteration()).isEqualTo(1);
        assertThat(restored.getCitations()).hasSize(1);
        assertThat(restored.getCitations().get(0).getChunkId()).isEqualTo("c1");
        assertThat(restored.getObservations()).hasSize(1);
        assertThat(restored.getDraftAnswer()).isEqualTo("草稿");
        assertThat(restored.getSkill().getName()).isEqualTo("wiki-qa");
        assertThat(restored.getTerminalStatus()).isEqualTo(AgentStatus.SUCCESS);
    }

    @Test
    void checkpointIsJavaSerializable() throws Exception {
        SkillDefinition skill = SkillDefinition.builder().name("s").build();
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t", "u", List.of()))
                .input("hi")
                .build();
        LoopState state = new LoopState(request, skill, new StepBudget(3, Duration.ofSeconds(10)));
        state.addObservation(ToolObservation.failed("t", "err"));

        Checkpoint original = state.toCheckpoint("run-x", NodeName.OBSERVE);

        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
            bytes = baos.toByteArray();
        }

        Checkpoint restored;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            restored = (Checkpoint) ois.readObject();
        }

        assertThat(restored.getRunId()).isEqualTo("run-x");
        assertThat(restored.getNextNode()).isEqualTo(NodeName.OBSERVE);
        assertThat(restored.getTraceId()).isEqualTo(original.getTraceId());
        assertThat(restored.getObservations()).hasSize(1);
        assertThat(restored.getObservations().get(0).isSuccess()).isFalse();
    }

    @Test
    void fromCheckpointResetsDeadline() {
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t", "u", List.of()))
                .input("hi")
                .build();
        LoopState original = new LoopState(request, null, new StepBudget(5, Duration.ofSeconds(1)));
        // 模拟旧 checkpoint：等待超过原始超时
        sleep(1200);

        Checkpoint checkpoint = original.toCheckpoint("r", NodeName.PLAN);
        LoopState restored = LoopState.fromCheckpoint(checkpoint);

        // 恢复后 deadline 基于当前时间重置，不应立即超时
        assertThat(restored.isTimedOut()).isFalse();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
