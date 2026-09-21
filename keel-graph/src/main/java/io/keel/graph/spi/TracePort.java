package io.keel.graph.spi;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.SkillDefinition;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;

/**
 * Trace 观测端口（FR-70）：把请求、每步、工具 IO、引用、Skill 版本上报给观测系统
 * （默认实现为 Langfuse，见 keel-langfuse）。
 *
 * <p>keel-graph 只定义接口，具体上报方式由实现决定。实现应保证上报不阻塞主流程
 * 且失败不影响请求结果。</p>
 */
public interface TracePort {

    /** 请求开始：创建 trace，记录输入与 skill 元数据。 */
    void onStart(AgentRequest request, SkillDefinition skill);

    /** 单步完成：记录节点执行结果（含工具 IO / 引用 / 草稿答案）。 */
    void onStep(NodeName node, LoopState state);

    /** 请求结束：记录最终结果，关闭 trace。result 可能为 null（极少情况）。 */
    void onEnd(AgentResult result);
}
