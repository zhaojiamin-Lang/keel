package io.keel.graph.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;
import io.keel.graph.StepBudget;

class StateObserverTest {

    @Test
    void snapshotReflectsCurrentState() {
        StepBudget budget = StepBudget.defaults();
        SkillDefinition skill = SkillDefinition.builder()
                .name("test")
                .version("2.0")
                .description("test skill")
                .build();
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t", "u", List.of()))
                .skill("test")
                .input("hello")
                .build();

        LoopState state = new LoopState(request, skill, budget);

        StateObserver.Snapshot snapshot = new StateObserver.Snapshot(state, NodeName.PLAN);

        assertThat(snapshot.getSkill().getVersion()).isEqualTo("2.0");
        assertThat(snapshot.getIteration()).isZero();
        assertThat(snapshot.getCurrentNode()).isEqualTo(NodeName.PLAN);
        assertThat(snapshot.getDraftAnswer()).isEmpty();
        assertThat(snapshot.getTraceId()).isNotBlank();
    }

    @Test
    void snapshotIsImmutable() {
        StepBudget budget = StepBudget.defaults();
        SkillDefinition skill = SkillDefinition.builder()
                .name("test")
                .description("test")
                .build();
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t", "u", List.of()))
                .input("hello")
                .build();

        LoopState state = new LoopState(request, skill, budget);
        StateObserver.Snapshot snapshot = new StateObserver.Snapshot(state, NodeName.PLAN);

        assertThat(snapshot.getIteration()).isZero();
    }

    /**
     * FR-14：真实状态只经 {@code onStep} 推送；无状态来源时 {@code snapshot()}
     * 返回空快照而非抛异常或伪造数据（业务可据此做 null 安全判断）。
     */
    @Test
    void defaultSnapshotIsEmptySinceStateIsPushed() {
        StateObserver observer = (node, snapshot) -> { };

        assertThat(observer.snapshot().getRequest()).isNull();
        assertThat(observer.snapshot().getTraceId()).isEmpty();
        assertThat(observer.snapshot().getCitations()).isEmpty();
        assertThat(observer.snapshot().getCurrentNode()).isNull();
    }
}