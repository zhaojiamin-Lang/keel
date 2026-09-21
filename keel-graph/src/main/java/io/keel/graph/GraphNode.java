package io.keel.graph;

/**
 * 图中的一个原子节点。节点只读写 {@link LoopState}，并返回下一节点名称；
 * 节点自身不做循环控制、不直接构造 AgentResult，便于单测和后续替换。
 */
public interface GraphNode {

    NodeName name();

    NodeName execute(LoopState state);
}
