package io.keel.graph.node;

import io.keel.core.OutboxRecord;
import io.keel.core.PendingAction;
import io.keel.core.SkillDefinition;
import io.keel.graph.GraphExecutionException;
import io.keel.graph.GraphNode;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;
import io.keel.graph.PlanDecision;
import io.keel.graph.ToolCall;
import io.keel.graph.ToolObservation;
import io.keel.graph.Verdict;
import io.keel.graph.spi.OutboxPort;
import io.keel.graph.spi.ToolPort;

/**
 * 工具执行节点。
 *
 * <p>两条路径：</p>
 * <ul>
 *     <li><b>未确认</b>：写身份由 {@link ToolPort#isWriteTool} 判定，写工具一律不执行，
 *         转为 {@link PendingAction} 走 GUARD（FR-13）；</li>
 *     <li><b>已确认</b>（{@code state.isWriteConfirmed()}）：执行 pendingAction 对应的写工具，
 *         幂等——若 observations 中已有同工具记录则跳过重复调用（FR-32）。</li>
 * </ul>
 * 只读工具在两条路径下都直接执行；越权工具由 PlanNode 先行拦截，此处做防御性复查。
 */
public final class ToolNode implements GraphNode {

    /** 观察摘要进入 OutboxRecord 的最大长度，防止大响应撑爆业务 outbox 表。 */
    private static final int SUMMARY_MAX_CHARS = 500;

    private final ToolPort toolPort;

    /** NFR-03：写路径 Outbox 钩子，可为 null（未注册时静默跳过）。 */
    private final OutboxPort outboxPort;

    public ToolNode(ToolPort toolPort) {
        // 兼容既有测试与未启用 Outbox 的装配路径
        this(toolPort, null);
    }

    public ToolNode(ToolPort toolPort, OutboxPort outboxPort) {
        this.toolPort = toolPort;
        this.outboxPort = outboxPort;
    }

    @Override
    public NodeName name() {
        return NodeName.TOOL;
    }

    @Override
    public NodeName execute(LoopState state) {
        // 确认执行路径：从 pendingAction 恢复工具调用，允许执行写工具
        if (state.isWriteConfirmed()) {
            return executeConfirmedWrite(state);
        }

        PlanDecision decision = state.getLastDecision();
        ToolCall call = decision.getToolCall();

        SkillDefinition skill = state.getSkill();
        if (skill != null && !skill.getTools().contains(call.getTool())) {
            state.setGuardVerdict(Verdict.rejected(
                    "TOOL_NOT_PERMITTED",
                    "skill '" + skill.getName() + "' 不允许调用工具: " + call.getTool()));
            return NodeName.GUARD;
        }
        if (toolPort == null) {
            throw new GraphExecutionException(
                    "NO_TOOL_PORT",
                    "模型请求调用工具但未配置 ToolPort: " + call.getTool());
        }
        if (toolPort.isWriteTool(call.getTool())) {
            // 双保险：写工具在此同样不执行，转为待确认动作（FR-13）
            // FR-32/FR-72：透传请求级幂等键，Harness 据此判定「写操作无幂等」
            // 缺幂等键 fail-closed 统一在 GuardNode.codeRules 处理，避免重复
            state.setPendingAction(new io.keel.core.PendingAction(
                    java.util.UUID.randomUUID().toString(),
                    call.getTool(),
                    call.getArgumentsJson(),
                    false,
                    state.getRequest().getIdempotencyKey()));
            return NodeName.GUARD;
        }

        ToolObservation observation;
        try {
            observation = toolPort.invoke(call, state.getRequest().getPrincipal());
        } catch (RuntimeException exception) {
            // 工具异常不击穿请求，转为失败观察交回 Planner，在预算内决策
            observation = ToolObservation.failed(call.getTool(), exception.getMessage());
        }
        state.addObservation(observation);
        return NodeName.OBSERVE;
    }

    /**
     * 确认执行写工具（FR-13/FR-32）。
     * <ul>
     *     <li>从 pendingAction 重建 ToolCall（checkpoint 恢复后 lastDecision 可能为 null）；</li>
     *     <li>幂等：observations 中已有同工具成功记录 → 直接走 OBSERVE，不重复调用；</li>
     *     <li>执行后清除 pendingAction，使后续 Guard 判定为 SUCCESS 而非 NEEDS_CONFIRM。</li>
     * </ul>
     */
    private NodeName executeConfirmedWrite(LoopState state) {
        PendingAction pending = state.getPendingAction();
        if (pending == null) {
            throw new GraphExecutionException(
                    "NO_PENDING_ACTION", "确认执行但 checkpoint 中无待确认动作");
        }
        if (toolPort == null) {
            throw new GraphExecutionException(
                    "NO_TOOL_PORT", "确认执行写工具但未配置 ToolPort: " + pending.getTool());
        }
        // 重建 ToolCall 时透传 PendingAction 的 idempotencyKey（FR-32），
        // 让 ToolPort 实现可据此对 server 端做跨进程 dedupe
        ToolCall call = new ToolCall(
                pending.getTool(),
                pending.getPayloadJson(),
                pending.getIdempotencyKey());

        // 幂等：同一 actionId 对应工具若已在 observations 中，跳过重复调用（FR-32）
        boolean alreadyExecuted = state.getObservations().stream()
                .anyMatch(obs -> pending.getTool().equals(obs.getTool()));
        if (!alreadyExecuted) {
            ToolObservation observation;
            try {
                // FR-31：确认后的写走独立端口方法，让 McpToolPort 等实现可以
                // 在此路径放开写限制（未确认路径 invoke 仍然 fail-closed）
                observation = toolPort.invokeConfirmedWrite(
                        call, state.getRequest().getPrincipal());
            } catch (RuntimeException exception) {
                observation = ToolObservation.failed(call.getTool(), exception.getMessage());
            }
            state.addObservation(observation);
            // NFR-03：写路径 Outbox 钩子——写真正执行（无论成败）才回调，业务在
            // 自己的本地事务里落 outbox 表。幂等跳过不回调，避免同一 idempotencyKey
            // 产生第二笔 outbox 记录（FR-32）。
            reportOutbox(state, pending, observation);
            // FR-63：真正执行了写动作才消耗写次数预算，幂等跳过不算
            state.incrementWriteCount();
        }
        // 写工具已执行（或幂等跳过），清除待确认动作，后续 GUARD 走 SUCCESS 出口
        state.setPendingAction(null);
        return NodeName.OBSERVE;
    }

    /**
     * NFR-03：把已执行的写动作交给业务 OutboxPort。
     * <p>回调异常不击穿请求——写已执行无法回滚，只能 best-effort；
     * 业务实现内部应自行保证落库可靠（事务、重试、告警）。</p>
     */
    private void reportOutbox(LoopState state, PendingAction pending, ToolObservation observation) {
        if (outboxPort == null) {
            return;
        }
        io.keel.core.AgentRequest request = state.getRequest();
        // 防御：principal 缺失时无法填充 OutboxRecord 必填的 tenantId/subjectId，
        // 显式跳过而不是靠 NPE 被上层吞掉（正常路径由 Builder/readObject 保证非空）
        if (request.getPrincipal() == null) {
            return;
        }
        OutboxRecord record;
        try {
            record = OutboxRecord.builder()
                    .tenantId(request.getPrincipal().getTenantId())
                    .subjectId(request.getPrincipal().getSubjectId())
                    .sessionId(request.getSessionId())
                    .skill(state.getSkill() == null ? null : state.getSkill().getName())
                    .actionId(pending.getActionId())
                    .tool(pending.getTool())
                    .idempotencyKey(pending.getIdempotencyKey())
                    .payloadJson(pending.getPayloadJson())
                    .success(observation.isSuccess())
                    .observationSummary(truncate(observation))
                    .build();
        } catch (RuntimeException exception) {
            // 组装失败同样不击穿请求，保持与回调失败一致的 best-effort 语义
            return;
        }
        try {
            outboxPort.record(record);
        } catch (RuntimeException exception) {
            // 吞掉：写已落地无法回滚，交给业务的可靠落库策略兜底
        }
    }

    private static String truncate(ToolObservation observation) {
        String summary = observation.isSuccess()
                ? observation.getContent()
                : observation.getErrorMessage();
        if (summary == null) {
            return "";
        }
        return summary.length() <= SUMMARY_MAX_CHARS
                ? summary
                : summary.substring(0, SUMMARY_MAX_CHARS) + "...";
    }
}
