package io.keel.graph;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.keel.core.AgentRequest;
import io.keel.core.AgentStatus;
import io.keel.core.Citation;
import io.keel.core.PendingAction;
import io.keel.core.SkillDefinition;

/**
 * 图内状态的可持久化快照（FR-12）。
 *
 * <p>包含从断点恢复所需的全部字段：runId、下一节点、以及 {@link LoopState} 的全量状态。
 * 注意 {@code deadlineNanos} 故意不持久化——恢复时由 {@link LoopState#fromCheckpoint}
 * 基于当前时间重新计算，避免旧超时值导致恢复后立即判定超时。</p>
 *
 * <p>本类实现 {@link Serializable}，具体存储介质（Redis 等）由 {@code CheckpointPort}
 * 实现负责序列化/反序列化。</p>
 */
public final class Checkpoint implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String runId;
    private final NodeName nextNode;
    private final AgentRequest request;
    private final SkillDefinition skill;
    private final StepBudget budget;
    private final String traceId;
    private final int iteration;
    /** 已执行的写动作次数（FR-63），与 {@link LoopState#getWriteCount()} 对应。 */
    private final int writeCount;
    private final List<Citation> citations;
    private final List<ToolObservation> observations;
    private final String draftAnswer;
    private final PlanDecision lastDecision;
    private final Verdict criticVerdict;
    private final Verdict guardVerdict;
    private final PendingAction pendingAction;
    private final AgentStatus terminalStatus;
    private final String terminalCode;

    public Checkpoint(
            String runId,
            NodeName nextNode,
            AgentRequest request,
            SkillDefinition skill,
            StepBudget budget,
            String traceId,
            int iteration,
            int writeCount,
            List<Citation> citations,
            List<ToolObservation> observations,
            String draftAnswer,
            PlanDecision lastDecision,
            Verdict criticVerdict,
            Verdict guardVerdict,
            PendingAction pendingAction,
            AgentStatus terminalStatus,
            String terminalCode) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.nextNode = Objects.requireNonNull(nextNode, "nextNode");
        this.request = Objects.requireNonNull(request, "request");
        this.skill = skill;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.iteration = iteration;
        this.writeCount = writeCount;
        this.citations = citations == null ? List.of() : new ArrayList<>(citations);
        this.observations = observations == null ? List.of() : new ArrayList<>(observations);
        this.draftAnswer = draftAnswer == null ? "" : draftAnswer;
        this.lastDecision = lastDecision;
        this.criticVerdict = criticVerdict;
        this.guardVerdict = guardVerdict;
        this.pendingAction = pendingAction;
        this.terminalStatus = terminalStatus;
        this.terminalCode = terminalCode == null ? "" : terminalCode;
    }

    public String getRunId() {
        return runId;
    }

    public NodeName getNextNode() {
        return nextNode;
    }

    public AgentRequest getRequest() {
        return request;
    }

    public SkillDefinition getSkill() {
        return skill;
    }

    public StepBudget getBudget() {
        return budget;
    }

    public String getTraceId() {
        return traceId;
    }

    public int getIteration() {
        return iteration;
    }

    public int getWriteCount() {
        return writeCount;
    }

    public List<Citation> getCitations() {
        return List.copyOf(citations);
    }

    public List<ToolObservation> getObservations() {
        return List.copyOf(observations);
    }

    public String getDraftAnswer() {
        return draftAnswer;
    }

    public PlanDecision getLastDecision() {
        return lastDecision;
    }

    public Verdict getCriticVerdict() {
        return criticVerdict;
    }

    public Verdict getGuardVerdict() {
        return guardVerdict;
    }

    public PendingAction getPendingAction() {
        return pendingAction;
    }

    public AgentStatus getTerminalStatus() {
        return terminalStatus;
    }

    public String getTerminalCode() {
        return terminalCode;
    }
}
