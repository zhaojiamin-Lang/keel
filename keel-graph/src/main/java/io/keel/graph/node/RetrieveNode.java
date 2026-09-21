package io.keel.graph.node;

import java.util.List;

import io.keel.core.Citation;
import io.keel.graph.GraphExecutionException;
import io.keel.graph.GraphNode;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;
import io.keel.graph.PlanDecision;
import io.keel.graph.spi.RetrievalPort;

/**
 * 检索节点：按 Planner 给出的 query 召回片段并写入状态。
 * 租户/权限过滤由 RetrievalPort 实现负责（FR-41/FR-60），本节点不做编排。
 */
public final class RetrieveNode implements GraphNode {

    private final RetrievalPort retrievalPort;

    public RetrieveNode(RetrievalPort retrievalPort) {
        this.retrievalPort = retrievalPort;
    }

    @Override
    public NodeName name() {
        return NodeName.RETRIEVE;
    }

    @Override
    public NodeName execute(LoopState state) {
        PlanDecision decision = state.getLastDecision();
        if (retrievalPort == null) {
            throw new GraphExecutionException(
                    "NO_RETRIEVAL_PORT",
                    "模型请求知识检索但未配置 RetrievalPort");
        }

        String query = decision.getText();
        List<Citation> retrieved = retrievalPort.retrieve(
                query, state.getRequest().getPrincipal());
        state.addCitations(retrieved);
        return NodeName.OBSERVE;
    }
}
