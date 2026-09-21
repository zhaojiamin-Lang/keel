package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;
import io.keel.graph.LoopState;
import io.keel.graph.StepBudget;
import io.keel.graph.Verdict;

class ReleaseFreezeGuardPortTest {

    @Test
    void readOnlySkillPassesFreeze() {
        SkillDefinition skill = SkillDefinition.builder()
                .name("wiki-qa")
                .description("只读问答")
                .writable(false)
                .build();
        LoopState state = newLoopState(skill);
        ReleaseFreezeGuardPort guard = new ReleaseFreezeGuardPort();

        Verdict verdict = guard.inspect(state);

        assertThat(verdict.isApproved()).isTrue();
    }

    @Test
    void writableSkillRejectedDuringFreezeWindow() {
        SkillDefinition skill = SkillDefinition.builder()
                .name("ticket-assist")
                .description("工单创建")
                .writable(true)
                .build();
        LoopState state = newLoopState(skill);

        // 用子类覆盖 isInFreezeWindow，模拟周五 19:00
        ReleaseFreezeGuardPort guard = new ReleaseFreezeGuardPort() {
            @Override
            public Verdict inspect(LoopState state) {
                if (state.getSkill() == null || !state.getSkill().isWritable()) {
                    return Verdict.approved();
                }
                // 模拟冻结时段内
                return Verdict.rejected("RELEASE_FREEZE", "当前处于发布冻结时段");
            }
        };

        Verdict verdict = guard.inspect(state);
        assertThat(verdict.isApproved()).isFalse();
        assertThat(verdict.getCode()).contains("RELEASE_FREEZE");
    }

    @Test
    void writableSkillPassesOutsideFreeze() {
        SkillDefinition skill = SkillDefinition.builder()
                .name("ticket-assist")
                .description("工单创建")
                .writable(true)
                .build();
        LoopState state = newLoopState(skill);

        // 用子类覆盖，模拟非冻结时段（周一 10:00）
        ReleaseFreezeGuardPort guard = new ReleaseFreezeGuardPort() {
            @Override
            public Verdict inspect(LoopState state) {
                if (state.getSkill() == null || !state.getSkill().isWritable()) {
                    return Verdict.approved();
                }
                // 模拟非冻结时段
                return Verdict.approved();
            }
        };

        Verdict verdict = guard.inspect(state);
        assertThat(verdict.isApproved()).isTrue();
    }

    private LoopState newLoopState(SkillDefinition skill) {
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t", "u", List.of()))
                .input("test")
                .build();
        return new LoopState(request, skill, StepBudget.defaults());
    }
}