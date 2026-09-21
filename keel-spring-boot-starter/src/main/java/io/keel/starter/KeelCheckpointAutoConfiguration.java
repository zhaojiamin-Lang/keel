package io.keel.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.keel.graph.spi.CheckpointPort;
import io.keel.memory.RedisCheckpointPort;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

/**
 * L1 Redis Checkpoint 自动装配（FR-12）。
 *
 * <p>仅当 classpath 存在 {@link RedisClient}（即引入了 keel-memory）且
 * {@code keel.checkpoint.enabled=true}（默认）时生效。
 * 业务可自行注册 {@link CheckpointPort} bean 覆盖默认 Redis 实现。</p>
 *
 * <p>生产可配项：密码 / DB index / SSL / 建连与命令超时 / 断线重连，见
 * {@link KeelProperties.Checkpoint}。</p>
 */
@AutoConfiguration
@ConditionalOnClass({RedisClient.class, CheckpointPort.class})
@ConditionalOnProperty(
        prefix = "keel.checkpoint",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(KeelProperties.class)
public class KeelCheckpointAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CheckpointPort.class)
    public CheckpointPort keelCheckpointPort(KeelProperties properties) {
        KeelProperties.Checkpoint cp = properties.getCheckpoint();
        return new RedisCheckpointPort(
                buildRedisUri(cp),
                cp.getTtl(),
                new RedisCheckpointPort.RedisOptions(
                        cp.getConnectTimeout(),
                        cp.getCommandTimeout(),
                        cp.getMaxRetries(),
                        cp.getRetryBackoff()));
    }

    /**
     * 组装 Redis 连接串。
     *
     * <p>密码 / DB / SSL 均为可选：未配置时与旧行为完全一致（无密码、DB 0、明文连接），
     * 保证已有部署升级后零变化。</p>
     */
    private RedisURI buildRedisUri(KeelProperties.Checkpoint cp) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(cp.getHost())
                .withPort(cp.getPort())
                .withDatabase(cp.getDatabase())
                .withSsl(cp.isSsl())
                .withTimeout(cp.getConnectTimeout());
        if (cp.getPassword() != null && !cp.getPassword().isBlank()) {
            builder.withPassword(cp.getPassword().toCharArray());
        }
        return builder.build();
    }
}
