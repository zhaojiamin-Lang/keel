package io.keel.graph.spi;

import java.util.Optional;

import io.keel.graph.Checkpoint;

/**
 * Checkpoint 存储端口（FR-12）：把图内状态持久化，支持进程重启后从断点恢复，
 * 避免重复执行已完成节点（尤其是写工具）。
 *
 * <p>keel-graph 只定义接口，具体实现由下游模块提供（默认 Redis，见 keel-memory）。
 * 存储介质、序列化方式、TTL 均由实现决定。</p>
 */
public interface CheckpointPort {

    /** 保存一次 checkpoint；同 runId 覆盖。实现需保证幂等。 */
    void save(Checkpoint checkpoint);

    /** 按 runId 加载最近一次 checkpoint；不存在返回 empty。 */
    Optional<Checkpoint> load(String runId);

    /** 请求终局后删除 checkpoint，避免残留。 */
    void delete(String runId);
}
