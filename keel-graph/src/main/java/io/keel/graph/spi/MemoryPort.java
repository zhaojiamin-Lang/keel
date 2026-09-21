package io.keel.graph.spi;

import java.util.List;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.MemoryBudget;
import io.keel.core.MemoryFragment;

/**
 * 记忆端口（FR-51/FR-52/FR-54）：L2 情景 + L3 长期记忆的召回与写入。
 *
 * <p>keel-graph 只定义接口，具体实现由 keel-memory 提供（默认 in-memory / Mongo / PG）。
 * 三层 key 必须带 tenant + principal（FR-53），实现自行保证隔离，图内核不信任任何
 * 未按 principal 过滤的返回。</p>
 */
public interface MemoryPort {

    /**
     * 按当前 principal 召回 L2 + L3 记忆，供注入 Loop。
     * 必须遵守 {@code budget}：条数与单条长度截断由实现负责（FR-54）。
     */
    List<MemoryFragment> recall(AgentRequest request, MemoryBudget budget);

    /**
     * 请求结束后写入情景轨迹（L2）。实现必须 best-effort：失败不得影响请求结果。
     */
    void record(AgentRequest request, AgentResult result);
}
