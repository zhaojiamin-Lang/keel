package io.keel.examples.ticket;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.keel.graph.Checkpoint;
import io.keel.graph.spi.CheckpointPort;

/**
 * 示例用内存 CheckpointPort（FR-12）。
 *
 * <p>确认执行写工具依赖 checkpoint 恢复状态。生产环境必须用
 * {@code RedisCheckpointPort}（keel-memory）——它跨实例共享，多实例部署下
 * 用户点「确认」的请求落到任意实例都能恢复待确认动作。内存实现做不到这点，
 * 因此示例在 application.yml 里<b>显式</b>写了 {@code keel.checkpoint.enabled=false}
 * 来声明「我知道这是单实例」。</p>
 *
 * <p>这也是 starter 在缺 keel-memory 且 {@code enabled=true} 时 fail-fast 的原因：
 * 与其让写确认在多实例下静默失效，不如让业务在启动期就被明确告知。</p>
 */
@Configuration
public class InMemoryCheckpointConfiguration {

    @Bean
    @ConditionalOnMissingBean(CheckpointPort.class)
    public CheckpointPort inMemoryCheckpointPort() {
        return new InMemoryCheckpointPort();
    }

    /** 进程内 Map 实现：仅单实例可用（见类注释）。 */
    static final class InMemoryCheckpointPort implements CheckpointPort {

        private final Map<String, Checkpoint> store = new ConcurrentHashMap<>();

        @Override
        public void save(Checkpoint checkpoint) {
            store.put(checkpoint.getRunId(), checkpoint);
        }

        @Override
        public Optional<Checkpoint> load(String runId) {
            return Optional.ofNullable(store.get(runId));
        }

        @Override
        public void delete(String runId) {
            store.remove(runId);
        }
    }
}
