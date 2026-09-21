package io.keel.starter;

import org.springframework.beans.factory.ObjectProvider;

import io.keel.graph.spi.CheckpointPort;
import io.keel.memory.RedisCheckpointPort;

/**
 * NFR-04 Redis 探针：对已装配的 {@link RedisCheckpointPort} 做真实 connect + PING。
 *
 * <p>职责边界（与「可选依赖不强制阻塞启动」的设计取舍一致）：探针只对
 * 「业务已选择启用」的子系统负责——
 * <ul>
 *   <li>未装配（classpath 缺 keel-memory 或 keel.checkpoint.enabled=false）→ 跳过，
 *       静默降级是既定设计；</li>
 *   <li>业务自备 CheckpointPort 实现（非 RedisCheckpointPort）→ 跳过，由业务自管；
 *       可自行注册 StartupProbe bean 探测自有实现。</li>
 * </ul></p>
 */
final class RedisCheckpointStartupProbe implements StartupProbe {

    private final KeelProperties properties;
    private final ObjectProvider<CheckpointPort> checkpointPorts;

    RedisCheckpointStartupProbe(KeelProperties properties, ObjectProvider<CheckpointPort> checkpointPorts) {
        this.properties = properties;
        this.checkpointPorts = checkpointPorts;
    }

    @Override
    public String check() {
        CheckpointPort port = checkpointPorts.getIfAvailable();
        if (!(port instanceof RedisCheckpointPort redis)) {
            return null;
        }
        try {
            redis.ping(properties.getStartup().getProbeTimeout());
            return null;
        } catch (Exception exception) {
            KeelProperties.Checkpoint checkpoint = properties.getCheckpoint();
            return "Redis: bean 'keelCheckpointPort'（RedisCheckpointPort）PING 失败: " + exception.getMessage()
                    + "；请检查 keel.checkpoint.host=" + checkpoint.getHost()
                    + " 与 keel.checkpoint.port=" + checkpoint.getPort()
                    + "（不需要 checkpoint 时设 keel.checkpoint.enabled=false）";
        }
    }
}
