package io.keel.graph.node;

import java.util.UUID;

import io.keel.core.PendingAction;
import io.keel.core.SkillDefinition;
import io.keel.graph.GraphExecutionException;
import io.keel.graph.GraphNode;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;
import io.keel.graph.PlanDecision;
import io.keel.graph.ToolCall;
import io.keel.graph.Verdict;
import io.keel.graph.spi.ModelPort;
import io.keel.graph.spi.ToolPort;

/**
 * 规划节点：调用模型决定下一步并完成路由。
 *
 * <p>这里同时承载两条代码化安全规则（不依赖 Prompt 自觉）：</p>
 * <ul>
 *     <li>FR-23/FR-35：工具不在 skill allowlist 内，直接拒绝，不回流给模型改写；</li>
 *     <li>FR-13：写工具一律转成 PendingAction 走 GUARD，模型用 CALL_TOOL 名义也绕不过。</li>
 * </ul>
 */
public final class PlanNode implements GraphNode {

    private final ModelPort modelPort;
    private final ToolPort toolPort;

    public PlanNode(ModelPort modelPort, ToolPort toolPort) {
        this.modelPort = modelPort;
        this.toolPort = toolPort;
    }

    @Override
    public NodeName name() {
        return NodeName.PLAN;
    }

    @Override
    public NodeName execute(LoopState state) {
        // 每进入一次规划算 1 步（FR-11 最大步数）
        state.incrementIteration();

        PlanDecision decision = modelPort.plan(state);
        state.setLastDecision(decision);

        switch (decision.getKind()) {
            case ANSWER:
                state.setDraftAnswer(decision.getText());
                return NodeName.CRITIC;
            case RETRIEVE:
                return NodeName.RETRIEVE;
            case PROPOSE_WRITE:
                return proposeWrite(state, decision.getToolCall());
            case CALL_TOOL:
            default:
                return callReadOnlyTool(state, decision.getToolCall());
        }
    }

    private NodeName callReadOnlyTool(LoopState state, ToolCall call) {
        NodeName rejection = rejectIfToolNotAllowed(state, call);
        if (rejection != null) {
            return rejection;
        }
        if (toolPort == null) {
            throw new GraphExecutionException(
                    "NO_TOOL_PORT",
                    "模型请求调用工具但未配置 ToolPort: " + call.getTool());
        }
        // 防御纵深：写身份以 ToolPort 代码判定为准，不信模型的动作分类
        if (toolPort.isWriteTool(call.getTool())) {
            return proposeWrite(state, call);
        }
        return NodeName.TOOL;
    }

    private NodeName proposeWrite(LoopState state, ToolCall call) {
        NodeName rejection = rejectIfToolNotAllowed(state, call);
        if (rejection != null) {
            return rejection;
        }
        // FR-13：只登记待确认动作，绝不执行；是否放行留给 Guard 与外部确认
        // FR-32/FR-72：写动作携带请求级幂等键，Harness 据此判定「写操作无幂等」
        // 缺幂等键的 fail-closed 在 GuardNode.codeRules 与 writable 检查合并处理，
        // 避免本节点硬规则与 Guard 重复，且保证 skill 不可写时优先返回 WRITE_FORBIDDEN_BY_SKILL
        String actionId = UUID.randomUUID().toString();
        state.setPendingAction(
                new PendingAction(
                        actionId,
                        call.getTool(),
                        call.getArgumentsJson(),
                        false,
                        state.getRequest().getIdempotencyKey()));
        return NodeName.GUARD;
    }

    /** skill 为 null 表示外层未施加 skill 约束（GuardedKeelAgent 路径总会带 skill）。 */
    private NodeName rejectIfToolNotAllowed(LoopState state, ToolCall call) {
        SkillDefinition skill = state.getSkill();
        if (skill != null && !skill.getTools().contains(call.getTool())) {
            state.setGuardVerdict(Verdict.rejected(
                    "TOOL_NOT_PERMITTED",
                    "skill '" + skill.getName() + "' 不允许调用工具: " + call.getTool()));
            return NodeName.GUARD;
        }
        return null;
    }
}
