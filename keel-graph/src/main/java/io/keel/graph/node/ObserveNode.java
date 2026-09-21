package io.keel.graph.node;

import io.keel.graph.GraphNode;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;

/**
 * 观察节点：工具/检索结果此刻已全部沉淀进状态，无条件回到 PLAN 进入下一轮。
 * 后续可在此插入观察摘要（供 FR-54 记忆预算截断），当前保持内核最小。
 */
public final class ObserveNode implements GraphNode {

    @Override
    public NodeName name() {
        return NodeName.OBSERVE;
    }

    @Override
    public NodeName execute(LoopState state) {
        return NodeName.PLAN;
    }
}
