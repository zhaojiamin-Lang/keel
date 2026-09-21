package io.keel.starter;

import io.keel.core.SkillDefinition;
import io.keel.graph.LoopState;
import io.keel.graph.Verdict;
import io.keel.graph.spi.GuardPort;
import io.keel.guard.CitationGuard;

/**
 * 把 {@link CitationGuard} 的引用校验规则接入图内 {@link GuardPort}（FR-64）。
 *
 * <p>语义与外层 {@code CitationGuard} 一致：skill 要求引用但结论未携带任何 citation 时，
 * 不允许以 SUCCESS 出门，降级为 UNCERTAIN。区别在于此处是图内终局 Guard 裁决，
 * 与 {@code CriticNode} 的「打回重试」形成纵深防御——即使 Critic 逻辑被绕过，
 * 无引用结论仍无法以 SUCCESS 走出 Guard 节点。</p>
 *
 * <p>其他 skill（不要求引用）一律放行，把 GuardPort 留给业务扩展（冻结窗口、写次数预算等）。</p>
 */
public class CitationGuardPort implements GuardPort {

    @Override
    public Verdict inspect(LoopState state) {
        SkillDefinition skill = state.getSkill();
        if (skill == null || !skill.isRequireCitation()) {
            return Verdict.approved();
        }
        if (!state.getCitations().isEmpty()) {
            return Verdict.approved();
        }
        // 与 CitationGuard.UNCERTAIN_TEXT 同语义：无检索依据，无法给出确定结论
        return Verdict.downgrade(
                "MISSING_CITATION",
                "require-citation 的结论必须携带 citation");
    }
}
