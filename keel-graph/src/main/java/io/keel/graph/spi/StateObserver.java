package io.keel.graph.spi;

import java.util.List;

import io.keel.core.AgentRequest;
import io.keel.core.AgentStatus;
import io.keel.core.Citation;
import io.keel.core.MemoryFragment;
import io.keel.core.PendingAction;
import io.keel.core.SkillDefinition;
import io.keel.graph.NodeName;
import io.keel.graph.PlanDecision;
import io.keel.graph.StepBudget;
import io.keel.graph.Verdict;
import io.keel.graph.LoopState;

/**
 * FR-14：每步状态对业务只读可见。
 *
 * <p>{@link LoopState} 封闭在 {@code io.keel.graph} 包内，外部无法直接读取。
 * 本端口提供只读快照：GraphKeelAgent 每执行完一个节点后调用
 * {@link #onStep(io.keel.graph.NodeName, Snapshot)} 把快照**推送**给观察者——
 * 观察者无需持有内核引用，也不必主动拉取。</p>
 *
 * <p>快照是某一时刻的状态副本，不保证实时一致；不应基于快照做写决策。</p>
 *
 * <p>与 {@link TracePort} 的关系：TracePort 面向观测系统（如 Langfuse），按请求 /
 * 步骤生命周期上报；StateObserver 面向业务侧状态消费（自定义监控、审计、外部 UI）。
 * 两者可同时接入，互不替代。</p>
 */
public interface StateObserver {

    /**
     * 每步完成回调：{@code node} 是刚执行完的节点，{@code snapshot} 是执行后的状态。
     *
     * <p>内核在每个节点执行后同步调用。实现必须快速返回；内核会捕获实现抛出的
     * 异常并忽略（观测失败不影响请求结果），但这只是兜底，不应依赖。</p>
     */
    void onStep(NodeName node, Snapshot snapshot);

    /**
     * 兼容入口：返回空快照。
     *
     * <p>{@code LoopState} 封闭在 graph 包内，外部无法自行构造有内容的快照；
     * 真实状态只通过 {@link #onStep} 推送。该方法保留是为了让既有实现类不必
     * 改动签名，业务应改用 {@link #onStep}。</p>
     */
    default Snapshot snapshot() {
        return Snapshot.empty();
    }

    /** 只读状态快照 */
    final class Snapshot {

        /** 无状态来源时的空快照（所有引用为空、数值为 0、列表为空） */
        private static final Snapshot EMPTY = new Snapshot();

        private final AgentRequest request;
        private final SkillDefinition skill;
        private final StepBudget budget;
        private final String traceId;
        private final int iteration;
        private final int writeCount;
        private final List<Citation> citations;
        private final String draftAnswer;
        private final PlanDecision lastDecision;
        private final Verdict criticVerdict;
        private final Verdict guardVerdict;
        private final PendingAction pendingAction;
        private final boolean writeConfirmed;
        private final AgentStatus terminalStatus;
        private final String terminalCode;
        private final List<MemoryFragment> memoryFragments;
        private final NodeName currentNode;

        /** 空快照构造：仅供 {@link #empty()} 使用 */
        private Snapshot() {
            this.request = null;
            this.skill = null;
            this.budget = null;
            this.traceId = "";
            this.iteration = 0;
            this.writeCount = 0;
            this.citations = List.of();
            this.draftAnswer = "";
            this.lastDecision = null;
            this.criticVerdict = null;
            this.guardVerdict = null;
            this.pendingAction = null;
            this.writeConfirmed = false;
            this.terminalStatus = null;
            this.terminalCode = "";
            this.memoryFragments = List.of();
            this.currentNode = null;
        }

        public Snapshot(LoopState state, NodeName currentNode) {
            this.request = state.getRequest();
            this.skill = state.getSkill();
            this.budget = state.getBudget();
            this.traceId = state.getTraceId();
            this.iteration = state.getIteration();
            this.writeCount = state.getWriteCount();
            this.citations = List.copyOf(state.getCitations());
            this.draftAnswer = state.getDraftAnswer();
            this.lastDecision = state.getLastDecision();
            this.criticVerdict = state.getCriticVerdict();
            this.guardVerdict = state.getGuardVerdict();
            this.pendingAction = state.getPendingAction();
            this.writeConfirmed = state.isWriteConfirmed();
            this.terminalStatus = state.getTerminalStatus();
            this.terminalCode = state.getTerminalCode();
            this.memoryFragments = List.copyOf(state.getMemoryFragments());
            this.currentNode = currentNode;
        }

        /** NFR-05：空快照——无 LoopState 可读时返回，便于业务做 null 安全判断 */
        static Snapshot empty() {
            return EMPTY;
        }

        public AgentRequest getRequest() { return request; }
        public SkillDefinition getSkill() { return skill; }
        public StepBudget getBudget() { return budget; }
        public String getTraceId() { return traceId; }
        public int getIteration() { return iteration; }
        public int getWriteCount() { return writeCount; }
        public List<Citation> getCitations() { return citations; }
        public String getDraftAnswer() { return draftAnswer; }
        public PlanDecision getLastDecision() { return lastDecision; }
        public Verdict getCriticVerdict() { return criticVerdict; }
        public Verdict getGuardVerdict() { return guardVerdict; }
        public PendingAction getPendingAction() { return pendingAction; }
        public boolean isWriteConfirmed() { return writeConfirmed; }
        public AgentStatus getTerminalStatus() { return terminalStatus; }
        public String getTerminalCode() { return terminalCode; }
        public List<MemoryFragment> getMemoryFragments() { return memoryFragments; }
        public NodeName getCurrentNode() { return currentNode; }
    }
}