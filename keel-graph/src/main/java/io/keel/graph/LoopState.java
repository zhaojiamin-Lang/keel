package io.keel.graph;

import java.util.ArrayList;
import java.util.List;

import io.keel.core.AgentRequest;
import io.keel.core.AgentStatus;
import io.keel.core.Citation;
import io.keel.core.MemoryFragment;
import io.keel.core.PendingAction;
import io.keel.core.SkillDefinition;

/**
 * 一次请求在图内流转时累积的可变状态（LangGraph state 风格）。
 *
 * <p>包内可见性刻意做成「节点可写、外部只读」：所有变更通过带语义的方法完成，
 * 未来加 checkpoint（FR-12）时只需围绕这一个类型做序列化。</p>
 */
public final class LoopState {

    private final AgentRequest request;
    private final SkillDefinition skill;
    private final StepBudget budget;
    private final String traceId;
    private final long deadlineNanos;

    /** 已进入 PLAN 的次数，每轮规划 +1，用于 FR-11 最大步数判定。 */
    private int iteration;

    /**
     * 已执行的写动作次数，每执行一个写工具 +1（幂等跳过不递增——未真正产生副作用），
     * 用于 FR-63 写次数预算判定。
     */
    private int writeCount;

    private final List<Citation> citations = new ArrayList<>();
    private final List<ToolObservation> observations = new ArrayList<>();

    /**
     * 本请求注入的历史记忆片段（L2/L3，FR-54）。运行时由 GraphKeelAgent 召回后填充，
     * 不进入 Checkpoint——记忆召回幂等，恢复时按当前请求重新召回即可。
     */
    private final List<MemoryFragment> memoryFragments = new ArrayList<>();

    private String draftAnswer = "";
    private PlanDecision lastDecision;
    private Verdict criticVerdict;
    private Verdict guardVerdict;
    private PendingAction pendingAction;

    /**
     * 请求级标志：当前请求是否携带了有效的 confirmedActionId（FR-13）。
     * 为 true 时 ToolNode 允许执行 pendingAction 对应的写工具。
     * 该标志不进入 Checkpoint——恢复时由 GraphKeelAgent 根据新请求重新设置。
     */
    private boolean writeConfirmed;

    /** 非空表示终局已被某个节点（Guard / Critic 降级）确定。 */
    private AgentStatus terminalStatus;
    private String terminalCode;

    public LoopState(AgentRequest request, SkillDefinition skill, StepBudget budget) {
        this(request, skill, budget, java.util.UUID.randomUUID().toString());
    }

    /**
     * 包内构造器：允许指定 traceId（恢复 checkpoint 时保留原 traceId）。
     * deadlineNanos 始终基于当前时间重新计算，避免恢复后沿用旧超时值。
     */
    LoopState(AgentRequest request, SkillDefinition skill, StepBudget budget, String traceId) {
        this.request = request;
        this.skill = skill;
        this.budget = budget;
        this.traceId = traceId;
        this.deadlineNanos = System.nanoTime() + budget.getTimeout().toNanos();
    }

    public AgentRequest getRequest() {
        return request;
    }

    /** 可能为 null（请求未指定 skill 时不加 skill 约束）。 */
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

    public void incrementIteration() {
        this.iteration++;
    }

    public int getWriteCount() {
        return writeCount;
    }

    /**
     * 由 ToolNode 在写工具真正执行后递增（FR-63）。
     * 幂等跳过的写动作不递增——未真正产生副作用不算预算消耗。
     */
    public void incrementWriteCount() {
        this.writeCount++;
    }

    public List<Citation> getCitations() {
        return List.copyOf(citations);
    }

    public void addCitations(List<Citation> retrieved) {
        if (retrieved != null) {
            citations.addAll(retrieved);
        }
    }

    public List<ToolObservation> getObservations() {
        return List.copyOf(observations);
    }

    public void addObservation(ToolObservation observation) {
        observations.add(observation);
    }

    public List<MemoryFragment> getMemoryFragments() {
        return List.copyOf(memoryFragments);
    }

    public void setMemoryFragments(List<MemoryFragment> fragments) {
        memoryFragments.clear();
        if (fragments != null) {
            memoryFragments.addAll(fragments);
        }
    }

    public String getDraftAnswer() {
        return draftAnswer;
    }

    public void setDraftAnswer(String draftAnswer) {
        this.draftAnswer = draftAnswer == null ? "" : draftAnswer;
    }

    public PlanDecision getLastDecision() {
        return lastDecision;
    }

    public void setLastDecision(PlanDecision lastDecision) {
        this.lastDecision = lastDecision;
    }

    public Verdict getCriticVerdict() {
        return criticVerdict;
    }

    public void setCriticVerdict(Verdict criticVerdict) {
        this.criticVerdict = criticVerdict;
    }

    public Verdict getGuardVerdict() {
        return guardVerdict;
    }

    public void setGuardVerdict(Verdict guardVerdict) {
        this.guardVerdict = guardVerdict;
    }

    public PendingAction getPendingAction() {
        return pendingAction;
    }

    public void setPendingAction(PendingAction pendingAction) {
        this.pendingAction = pendingAction;
    }

    public boolean isWriteConfirmed() {
        return writeConfirmed;
    }

    /**
     * 由 GraphKeelAgent 在确认请求入口设置：校验 checkpoint 中 pendingAction.actionId
     * 与请求 confirmedActionId 一致后置 true（FR-13/FR-32）。
     */
    public void setWriteConfirmed(boolean writeConfirmed) {
        this.writeConfirmed = writeConfirmed;
    }

    public AgentStatus getTerminalStatus() {
        return terminalStatus;
    }

    public String getTerminalCode() {
        return terminalCode;
    }

    /** 节点确定终局（SUCCESS / NEEDS_CONFIRM / REJECTED / UNCERTAIN）。 */
    public void terminate(AgentStatus status, String code) {
        this.terminalStatus = status;
        this.terminalCode = code == null ? "" : code;
    }

    /** 每跳边界检查：是否已超过整体墙钟超时预算（FR-11）。 */
    public boolean isTimedOut() {
        return System.nanoTime() > deadlineNanos;
    }

    /** Critic 打回后是否还允许再规划一轮。 */
    public boolean hasIterationBudgetLeft() {
        return iteration < budget.getMaxIterations();
    }

    /**
     * 把当前状态快照为可持久化的 {@link Checkpoint}（FR-12）。
     * {@code nextNode} 为恢复后应进入的下一个节点。
     */
    public Checkpoint toCheckpoint(String runId, NodeName nextNode) {
        return new Checkpoint(
                runId,
                nextNode,
                request,
                skill,
                budget,
                traceId,
                iteration,
                writeCount,
                citations,
                observations,
                draftAnswer,
                lastDecision,
                criticVerdict,
                guardVerdict,
                pendingAction,
                terminalStatus,
                terminalCode);
    }

    /**
     * 从 checkpoint 重建 LoopState（FR-12）。
     * deadlineNanos 基于当前时间重新计算，traceId 保留原值。
     */
    public static LoopState fromCheckpoint(Checkpoint checkpoint) {
        LoopState state = new LoopState(
                checkpoint.getRequest(),
                checkpoint.getSkill(),
                checkpoint.getBudget(),
                checkpoint.getTraceId());
        state.iteration = checkpoint.getIteration();
        state.writeCount = checkpoint.getWriteCount();
        state.citations.addAll(checkpoint.getCitations());
        state.observations.addAll(checkpoint.getObservations());
        state.draftAnswer = checkpoint.getDraftAnswer();
        state.lastDecision = checkpoint.getLastDecision();
        state.criticVerdict = checkpoint.getCriticVerdict();
        state.guardVerdict = checkpoint.getGuardVerdict();
        state.pendingAction = checkpoint.getPendingAction();
        state.terminalStatus = checkpoint.getTerminalStatus();
        state.terminalCode = checkpoint.getTerminalCode();
        return state;
    }
}
