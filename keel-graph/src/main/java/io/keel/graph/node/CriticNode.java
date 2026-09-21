package io.keel.graph.node;

import io.keel.core.AgentStatus;
import io.keel.core.SkillDefinition;
import io.keel.graph.GraphNode;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;
import io.keel.graph.Verdict;
import io.keel.graph.spi.ModelPort;

/**
 * Critic 节点：核对草稿答案。
 *
 * <p>顺序遵循铁律 4——先跑代码硬规则（require-citation 却零引用直接打回，FR-42），
 * 再让模型做补充裁决（如引用与陈述对不上）。被打回时优先回 PLAN 重试（FR-10/FR-43）；
 * 步数预算耗尽则降级为 UNCERTAIN，绝不让无依据结论以 SUCCESS 出门。</p>
 */
public final class CriticNode implements GraphNode {

    /** 预算耗尽、无法形成有依据结论时的统一降级话术。 */
    public static final String UNCERTAIN_TEXT = "信息不足或引用无法核对，无法给出确定结论。";

    private final ModelPort modelPort;

    public CriticNode(ModelPort modelPort) {
        this.modelPort = modelPort;
    }

    @Override
    public NodeName name() {
        return NodeName.CRITIC;
    }

    @Override
    public NodeName execute(LoopState state) {
        Verdict verdict = codeCritique(state);
        if (verdict == null) {
            verdict = modelPort.critique(state);
        }
        state.setCriticVerdict(verdict);

        if (verdict.isApproved()) {
            return NodeName.GUARD;
        }
        if (state.hasIterationBudgetLeft()) {
            return NodeName.PLAN;
        }

        // 预算耗尽：降级「不确定」（FR-43），把最后一次拒绝原因挂在 terminalCode 上
        state.setDraftAnswer(UNCERTAIN_TEXT);
        state.terminate(AgentStatus.UNCERTAIN, verdict.getCode());
        return NodeName.END;
    }

    /** 代码硬规则；返回 null 表示交给模型 Critic 继续裁决。 */
    private Verdict codeCritique(LoopState state) {
        SkillDefinition skill = state.getSkill();
        if (skill != null && skill.isRequireCitation() && state.getCitations().isEmpty()) {
            return Verdict.rejected(
                    "MISSING_CITATION",
                    "require-citation 的结论必须携带 citation");
        }
        return null;
    }
}
