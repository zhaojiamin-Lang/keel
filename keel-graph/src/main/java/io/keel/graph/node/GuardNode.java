package io.keel.graph.node;

import io.keel.core.AgentStatus;
import io.keel.core.PendingAction;
import io.keel.core.SkillDefinition;
import io.keel.graph.GraphNode;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;
import io.keel.graph.Verdict;
import io.keel.graph.spi.GuardPort;

/**
 * Guard 终局节点，裁决优先级：
 * <ol>
 *     <li>上游节点已写入的代码裁决（如 TOOL_NOT_PERMITTED，FR-35）；</li>
 *     <li>本节点硬规则：skill 不可写却出现待确认写动作 → 拒绝（FR-13）；</li>
 *     <li>业务扩展 {@link GuardPort}（缺省放行）。</li>
 * </ol>
 * 放行时：有待确认动作 → NEEDS_CONFIRM；否则 SUCCESS。拒绝 → REJECTED。
 */
public final class GuardNode implements GraphNode {

    private final GuardPort guardPort;

    public GuardNode(GuardPort guardPort) {
        this.guardPort = guardPort;
    }

    @Override
    public NodeName name() {
        return NodeName.GUARD;
    }

    @Override
    public NodeName execute(LoopState state) {
        Verdict verdict = state.getGuardVerdict();
        if (verdict == null) {
            verdict = codeRules(state);
        }
        if (verdict == null) {
            verdict = guardPort.inspect(state);
        }
        state.setGuardVerdict(verdict);

        if (!verdict.isApproved()) {
            // downgrade：信息不足降级为 UNCERTAIN（如缺引用）；rejected：硬拒绝（如越权写）
            if (verdict.isDowngrade()) {
                state.setDraftAnswer(CriticNode.UNCERTAIN_TEXT);
                state.terminate(AgentStatus.UNCERTAIN, verdict.getCode());
            } else {
                state.terminate(AgentStatus.REJECTED, verdict.getCode());
            }
            return NodeName.END;
        }

        if (state.getPendingAction() != null) {
            // Guard 放行不等于可以执行：仍需外部确认（FR-13 默认行为）
            state.terminate(AgentStatus.NEEDS_CONFIRM, "");
        } else {
            state.terminate(AgentStatus.SUCCESS, "");
        }
        return NodeName.END;
    }

    /**
     * 图自带的写权限硬规则，不依赖业务 GuardPort 是否实现。
     * <ol>
     *     <li>skill 不可写却出现待确认写动作 → WRITE_FORBIDDEN_BY_SKILL（FR-13）；</li>
     *     <li>skill 可写但缺请求级 idempotencyKey → WRITE_WITHOUT_IDEMPOTENCY（FR-32 P0 fail-closed）。</li>
     * </ol>
     * 检查顺序：writable 优先，因为 skill 不可写比缺幂等键更基础——根本就不该有写动作。
     */
    private Verdict codeRules(LoopState state) {
        PendingAction pending = state.getPendingAction();
        if (pending == null) {
            return null;
        }
        SkillDefinition skill = state.getSkill();
        if (skill == null || !skill.isWritable()) {
            String label = skill == null ? "<none>" : "'" + skill.getName() + "'";
            return Verdict.rejected(
                    "WRITE_FORBIDDEN_BY_SKILL",
                    "skill " + label + " 不可写，写工具 " + pending.getTool() + " 已被拦截");
        }
        // FR-32 P0 fail-closed（铁律 4）：写动作必须带请求级幂等键，
        // 否则 server 端无法跨进程 dedupe，Harness 也无法判定「写操作无幂等」。
        // 此处代码侧硬拒绝，不依赖 Prompt 自觉。
        String idempotencyKey = pending.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Verdict.rejected(
                    "WRITE_WITHOUT_IDEMPOTENCY",
                    "写工具 " + pending.getTool() + " 缺少 idempotencyKey，拒绝放行（FR-32）");
        }
        return null;
    }
}
