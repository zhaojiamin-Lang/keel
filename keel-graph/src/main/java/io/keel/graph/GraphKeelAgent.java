package io.keel.graph;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.MemoryBudget;
import io.keel.core.PendingAction;
import io.keel.core.SkillDefinition;
import io.keel.core.SkillGrayPolicy;
import io.keel.core.SkillRouter;
import io.keel.graph.spi.StateObserver;
import io.keel.graph.node.CriticNode;
import io.keel.graph.node.GuardNode;
import io.keel.graph.node.ObserveNode;
import io.keel.graph.node.PlanNode;
import io.keel.graph.node.RetrieveNode;
import io.keel.graph.node.ToolNode;
import io.keel.graph.spi.CheckpointPort;
import io.keel.graph.spi.GuardPort;
import io.keel.graph.spi.MemoryPort;
import io.keel.graph.spi.OutboxPort;
import io.keel.graph.spi.ModelPort;
import io.keel.graph.spi.RetrievalPort;
import io.keel.graph.spi.ToolPort;
import io.keel.graph.spi.TracePort;

/**
 * LangGraph 风格的默认图执行器（FR-10/FR-11/FR-13），{@link KeelAgent} 的图实现。
 *
 * <p>本类只负责：装配节点、驱动 PLAN 起始的状态循环、在跳边界执行步数/超时预算、
 * 把终局状态翻译成 {@link AgentResult}。模型、检索、工具、Guard 均为端口，
 * keel-graph 不依赖 Spring AI / Web / 向量库。</p>
 */
public final class GraphKeelAgent implements KeelAgent {

    private static final Logger log = LoggerFactory.getLogger(GraphKeelAgent.class);

    private final ModelPort modelPort;
    private final RetrievalPort retrievalPort;
    private final ToolPort toolPort;
    private final GuardPort guardPort;
    private final CheckpointPort checkpointPort;
    private final TracePort tracePort;
    private final MemoryPort memoryPort;
    private final OutboxPort outboxPort;
    private final MemoryBudget memoryBudget;
    private final StepBudget budget;
    private final Function<String, Optional<SkillDefinition>> skillResolver;
    private final SkillRouter skillRouter;
    private final java.util.function.Supplier<List<SkillDefinition>> skillCandidates;
    /** 可选灰度策略（§8 第三期）；不配置 = 现行为零变化。 */
    private final SkillGrayPolicy skillGrayPolicy;
    private final StateObserver stateObserver;
    private final Map<NodeName, GraphNode> nodes;

    private GraphKeelAgent(Builder builder) {
        this.modelPort = builder.modelPort;
        this.retrievalPort = builder.retrievalPort;
        this.toolPort = builder.toolPort;
        this.guardPort = builder.guardPort;
        this.checkpointPort = builder.checkpointPort;
        this.tracePort = builder.tracePort;
        this.memoryPort = builder.memoryPort;
        this.outboxPort = builder.outboxPort;
        this.memoryBudget = builder.memoryBudget;
        this.budget = builder.budget;
        this.skillResolver = builder.skillResolver;
        this.skillRouter = builder.skillRouter;
        this.skillCandidates = builder.skillCandidates;
        this.skillGrayPolicy = builder.skillGrayPolicy;
        this.stateObserver = builder.stateObserver;
        this.nodes = registerNodes();
    }

    public static Builder builder() {
        return new Builder();
    }

    private Map<NodeName, GraphNode> registerNodes() {
        Map<NodeName, GraphNode> map = new EnumMap<>(NodeName.class);
        map.put(NodeName.PLAN, new PlanNode(modelPort, toolPort));
        map.put(NodeName.RETRIEVE, new RetrieveNode(retrievalPort));
        map.put(NodeName.TOOL, new ToolNode(toolPort, outboxPort));
        map.put(NodeName.OBSERVE, new ObserveNode());
        map.put(NodeName.CRITIC, new CriticNode(modelPort));
        map.put(NodeName.GUARD, new GuardNode(guardPort));
        return Map.copyOf(map);
    }

    @Override
    public AgentResult run(AgentRequest request) {
        if (request == null || request.getInput() == null || request.getInput().isBlank()) {
            return terminalError(null, "INVALID_REQUEST", "request or input is required");
        }

        SkillDefinition skill = null;
        String skillName = request.getSkill();
        if (skillName != null && !skillName.isBlank()) {
            // 显式指定优先（用户意图明确）
            skill = skillResolver.apply(skillName).orElse(null);
            if (skill == null) {
                return terminalError(null, "UNKNOWN_SKILL", "skill not found: " + skillName);
            }
        } else if (skillRouter != null) {
            // FR-21：未显式指定时自动路由，低置信回退 chat（skill=null）
            List<SkillDefinition> candidates = resolveAllSkills();
            skill = skillRouter.route(request, candidates)
                    .map(SkillRouter.RoutingResult::skill)
                    .orElse(null);
        }

        // 灰度收口（§8 第三期）：显式指定与自动路由两条路径统一在这里应用灰度策略；
        // 策略返回 empty 时沿用稳定版。candidates 只含稳定版，灰度版本不可能
        // 被路由直接选中，必须经过策略的确定性判定（sticky）
        if (skill != null && skillGrayPolicy != null) {
            skill = skillGrayPolicy.select(request, skill).orElse(skill);
        }

        // FR-70：trace 在 skill resolve 后启动，贯穿整个请求生命周期
        if (tracePort != null) {
            tracePort.onStart(request, skill);
        }

        // FR-12/FR-53：checkpoint 的 runId 带 tenant + principal 前缀，保证跨租户隔离；
        // 无 sessionId 则跳过持久化
        String sessionId = request.getSessionId();
        String runId = null;
        // 防御：principal 缺失（正常路径由 Builder/readObject 保证非空）时无法构建
        // 隔离 runId，跳过持久化——无身份请求不做跨请求 checkpoint 恢复
        if (sessionId != null && !sessionId.isBlank() && request.getPrincipal() != null) {
            io.keel.core.KeelPrincipal principal = request.getPrincipal();
            runId = "keel:checkpoint:"
                    + principal.getTenantId() + ":"
                    + principal.getSubjectId() + ":"
                    + sessionId;
        }
        boolean checkpointing = checkpointPort != null && runId != null;

        LoopState state;
        NodeName current;

        // FR-13/FR-32：确认执行路径——请求带 confirmedActionId 时，从 checkpoint 恢复
        // 并校验 actionId 一致性，然后从 TOOL 节点执行写工具（幂等由 ToolNode 保证）
        String confirmedActionId = request.getConfirmedActionId();
        if (confirmedActionId != null && !confirmedActionId.isBlank()) {
            if (!checkpointing) {
                return terminalError(null, "CONFIRM_REQUIRES_SESSION",
                        "确认写动作必须提供 sessionId 以恢复 checkpoint");
            }
            Optional<Checkpoint> saved = checkpointPort.load(runId);
            if (saved.isEmpty()) {
                return terminalError(null, "CONFIRM_CHECKPOINT_NOT_FOUND",
                        "确认写动作但未找到对应 checkpoint: " + runId);
            }
            state = LoopState.fromCheckpoint(saved.get());
            PendingAction pending = state.getPendingAction();
            if (pending == null) {
                return terminalError(null, "CONFIRM_NO_PENDING_ACTION",
                        "确认写动作但 checkpoint 中无待确认动作");
            }
            if (!confirmedActionId.equals(pending.getActionId())) {
                return terminalError(null, "CONFIRM_ACTION_ID_MISMATCH",
                        "确认的 actionId 与待确认动作不一致");
            }
            state.setWriteConfirmed(true);
            current = NodeName.TOOL;
        } else if (checkpointing) {
            Optional<Checkpoint> saved = checkpointPort.load(runId);
            if (saved.isPresent()) {
                // 从断点恢复：保留已执行节点的状态，从下一节点继续，不重复调用工具
                state = LoopState.fromCheckpoint(saved.get());
                current = saved.get().getNextNode();
            } else {
                state = new LoopState(request, skill, budget);
                current = NodeName.PLAN;
            }
        } else {
            state = new LoopState(request, skill, budget);
            current = NodeName.PLAN;
        }

        // FR-54：请求开始即召回 L2/L3 记忆注入 Loop（召回幂等，恢复后按当前请求重新召回）
        if (memoryPort != null) {
            try {
                state.setMemoryFragments(memoryPort.recall(request, memoryBudget));
            } catch (RuntimeException e) {
                // FR-54：记忆召回是旁路，失败按无记忆继续、不阻塞请求；
                // 但必须留痕告警，否则「agent 像失忆」类问题无从排查
                log.warn("记忆召回失败，按无记忆继续（tenant={}，sessionId={}）",
                        request.getPrincipal().getTenantId(), request.getSessionId(), e);
            }
        }

        AgentResult result = null;
        try {
            while (current != NodeName.END) {
                // FR-11：超时每跳边界检查；最大步数只在重新进入 PLAN 前检查
                if (state.isTimedOut()) {
                    result = terminalError(
                            state,
                            "LOOP_TIMEOUT",
                            "Loop 已超过超时预算: " + budget.getTimeout());
                    break;
                }
                if (current == NodeName.PLAN
                        && state.getIteration() >= budget.getMaxIterations()) {
                    result = terminalError(
                            state,
                            "MAX_STEPS_EXCEEDED",
                            "已达到最大规划步数: " + budget.getMaxIterations());
                    break;
                }
                // FR-63：写次数预算——任一节点跳边界检查，超限即停止
                if (state.getWriteCount() >= budget.getMaxWrites()) {
                    result = terminalError(
                            state,
                            "MAX_WRITES_EXCEEDED",
                            "已达到最大写次数: " + budget.getMaxWrites());
                    break;
                }

                GraphNode node = nodes.get(current);
                NodeName executed = current;
                current = node.execute(state);

                // FR-70：每步完成后上报节点执行结果
                if (tracePort != null) {
                    tracePort.onStep(executed, state);
                }
                // FR-14：每步状态对业务只读可见——推送快照给观察者（而非无参拉取）。
                // 观测失败不影响请求结果，故吞掉实现抛出的异常（SPI 已约定实现不应抛）
                if (stateObserver != null) {
                    try {
                        stateObserver.onStep(executed, new StateObserver.Snapshot(state, current));
                    } catch (RuntimeException ignored) {
                        // 观测是旁路，任何异常都不能击穿 Loop
                    }
                }

                // FR-12：每跳执行后持久化；nextNode 即恢复后的起点，保证已执行节点不重复
                if (checkpointing) {
                    checkpointPort.save(state.toCheckpoint(runId, current));
                }
            }
            if (result == null) {
                result = toResult(state);
                // NEEDS_CONFIRM 保留 checkpoint 供后续确认请求恢复（FR-13）；
                // 其他终局状态清理，避免同 sessionId 后续请求误恢复旧状态
                if (checkpointing && result.getStatus() != AgentStatus.NEEDS_CONFIRM) {
                    checkpointPort.delete(runId);
                }
            }
        } catch (GraphExecutionException exception) {
            if (checkpointing) {
                checkpointPort.delete(runId);
            }
            result = terminalError(state, exception.getCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            if (checkpointing) {
                checkpointPort.delete(runId);
            }
            result = terminalError(state, "LOOP_NODE_ERROR", exception.getMessage());
        } finally {
            // FR-70：无论正常/异常都关闭 trace
            if (tracePort != null) {
                tracePort.onEnd(result);
            }
            // FR-51：请求结束后 best-effort 写入 L2 情景记忆，失败不影响结果
            if (memoryPort != null && result != null) {
                try {
                    memoryPort.record(request, result);
                } catch (RuntimeException ignored) {
                    // 记忆写入失败绝不影响请求结果
                }
            }
        }
        return result;
    }

    /** 终局状态 → AgentResult；REJECTED 的文本取 Guard 给人的可读原因。 */
    private AgentResult toResult(LoopState state) {
        AgentStatus status = state.getTerminalStatus();
        String text = state.getDraftAnswer();
        String errorCode = "";
        String errorMessage = "";

        if (status == AgentStatus.REJECTED && state.getGuardVerdict() != null) {
            text = state.getGuardVerdict().getReason();
            errorCode = state.getTerminalCode();
            errorMessage = state.getGuardVerdict().getReason();
        } else if (status == AgentStatus.UNCERTAIN) {
            // 降级原因（如 MISSING_CITATION）挂到 errorCode 便于排查，不改变 UNCERTAIN 语义
            errorCode = state.getTerminalCode();
        }

        return AgentResult.builder()
                .status(status)
                .text(text)
                .citations(state.getCitations())
                .pendingActions(state.getPendingAction() == null
                        ? List.of()
                        : List.of(state.getPendingAction()))
                .traceId(state.getTraceId())
                .errorCode(errorCode.isBlank() ? null : errorCode)
                .errorMessage(errorMessage.isBlank() ? null : errorMessage)
                .build();
    }

    /**
     * FR-21：收集所有已注册 skill 作为路由候选。
     * starter 装配时传 skillCandidates（= SkillRegistry::all）；
     * 缺省返回空列表（不路由，回退 chat）。
     */
    private List<SkillDefinition> resolveAllSkills() {
        return skillCandidates != null ? skillCandidates.get() : List.of();
    }

    private AgentResult terminalError(LoopState state, String code, String message) {
        String traceId = state == null ? UUID.randomUUID().toString() : state.getTraceId();
        return AgentResult.builder()
                .status(AgentStatus.ERROR)
                .text("")
                .citations(state == null ? List.of() : state.getCitations())
                .pendingActions(List.of())
                .traceId(traceId)
                .errorCode(code)
                .errorMessage(message)
                .build();
    }

    public static final class Builder {

        private ModelPort modelPort;
        private RetrievalPort retrievalPort;
        private ToolPort toolPort;
        private GuardPort guardPort = GuardPort.allowAll();
        private CheckpointPort checkpointPort;
        private TracePort tracePort;
        private MemoryPort memoryPort;
        private OutboxPort outboxPort;
        private MemoryBudget memoryBudget = MemoryBudget.defaults();
        private StepBudget budget = StepBudget.defaults();
        private Function<String, Optional<SkillDefinition>> skillResolver = name -> Optional.empty();
        private SkillRouter skillRouter;
        private java.util.function.Supplier<List<SkillDefinition>> skillCandidates;
        private SkillGrayPolicy skillGrayPolicy;
        private StateObserver stateObserver;

        private Builder() {
        }

        public Builder modelPort(ModelPort modelPort) {
            this.modelPort = modelPort;
            return this;
        }

        public Builder retrievalPort(RetrievalPort retrievalPort) {
            this.retrievalPort = retrievalPort;
            return this;
        }

        public Builder toolPort(ToolPort toolPort) {
            this.toolPort = toolPort;
            return this;
        }

        public Builder guardPort(GuardPort guardPort) {
            this.guardPort = guardPort;
            return this;
        }

        /** 可选：未设置则不做 checkpoint（FR-12）。 */
        public Builder checkpointPort(CheckpointPort checkpointPort) {
            this.checkpointPort = checkpointPort;
            return this;
        }

        /** 可选：未设置则不上报 trace（FR-70）。 */
        public Builder tracePort(TracePort tracePort) {
            this.tracePort = tracePort;
            return this;
        }

        /** 可选：未设置则不注入记忆（FR-54）。 */
        public Builder memoryPort(MemoryPort memoryPort) {
            this.memoryPort = memoryPort;
            return this;
        }

        /** 记忆注入预算（FR-54）；仅在设置 memoryPort 时生效，缺省用默认预算。 */
        public Builder memoryBudget(MemoryBudget memoryBudget) {
            this.memoryBudget = memoryBudget;
            return this;
        }

        /** 可选：未设置则写路径不触发 Outbox 钩子（NFR-03），业务自备 bean 才生效。 */
        public Builder outboxPort(OutboxPort outboxPort) {
            this.outboxPort = outboxPort;
            return this;
        }

        public Builder budget(StepBudget budget) {
            this.budget = budget;
            return this;
        }

        /** starter 装配时传 SkillRegistry::get；缺省解析不到任何 skill。 */
        public Builder skillResolver(Function<String, Optional<SkillDefinition>> skillResolver) {
            this.skillResolver = skillResolver;
            return this;
        }

        /** FR-21：skill 自动路由器（可选），未配置时不路由，保留现有行为 */
        public Builder skillRouter(SkillRouter skillRouter) {
            this.skillRouter = skillRouter;
            return this;
        }

        /** FR-21：候选 skill 供应商（= SkillRegistry::all），用于自动路由 */
        public Builder skillCandidates(java.util.function.Supplier<List<SkillDefinition>> skillCandidates) {
            this.skillCandidates = skillCandidates;
            return this;
        }

        /**
         * 可选灰度策略（§8 第三期）：同名 skill 灰度/稳定版本共存时按请求选版本。
         * 不配置 = 永远使用稳定版本（现行为零变化）。
         */
        public Builder skillGrayPolicy(SkillGrayPolicy skillGrayPolicy) {
            this.skillGrayPolicy = skillGrayPolicy;
            return this;
        }

        /** FR-14：状态观察端口（可选），每一步执行后回调 */
        public Builder stateObserver(StateObserver stateObserver) {
            this.stateObserver = stateObserver;
            return this;
        }

        public GraphKeelAgent build() {
            Objects.requireNonNull(modelPort, "modelPort");
            Objects.requireNonNull(guardPort, "guardPort");
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(skillResolver, "skillResolver");
            return new GraphKeelAgent(this);
        }
    }
}
