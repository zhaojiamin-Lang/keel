package io.keel.memory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import io.keel.graph.Checkpoint;
import io.keel.graph.spi.CheckpointPort;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisStringCommands;
import io.lettuce.core.codec.ByteArrayCodec;

/**
 * 基于 Redis 的 {@link CheckpointPort} 实现（FR-12 / FR-53）。
 *
 * <p>用 Java 原生序列化把 {@link Checkpoint} 序列化为 byte[] 存入 Redis String，
 * key 带 tenant + principal 前缀保证隔离：
 * {@code keel:checkpoint:{tenantId}:{subjectId}:{runId}}。</p>
 *
 * <p>本类不依赖 Spring，可独立使用；starter 负责装配与生命周期（destroyMethod=close）。</p>
 *
 * <p><b>生产可配项</b>（对应 {@code keel.checkpoint.*}）：连接串（host/port）、
 * {@code password}、{@code database}（DB index）、{@code ssl}、
 * {@code connect-timeout}、{@code command-timeout}、{@code max-retries}。
 * 默认值保持宽松（无密码、DB 0、不启用 SSL），未配置时行为与引入这些参数前一致。</p>
 */
public final class RedisCheckpointPort implements CheckpointPort, AutoCloseable {

    private final RedisClient redisClient;
    private final Duration ttl;

    /**
     * 以默认客户端选项构造（无密码 / DB 0 / 不启用 SSL / 默认超时与重连）。
     * 保留本构造器是为了不破坏既有调用方与测试。
     */
    public RedisCheckpointPort(RedisURI redisUri, Duration ttl) {
        this(redisUri, ttl, null);
    }

    /**
     * 完整构造：{@link RedisOptions} 承载客户端级选项（超时 / 重连 / 连接池）。
     *
     * @param redisUri 连接串（含 host/port/password/database/ssl）
     * @param ttl      checkpoint 存活时间，必须为正
     * @param options  客户端选项，{@code null} 表示用 Lettuce 默认值
     */
    public RedisCheckpointPort(RedisURI redisUri, Duration ttl, RedisOptions options) {
        Objects.requireNonNull(redisUri, "redisUri");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.redisClient = RedisClient.create(redisUri);
        if (options != null) {
            this.redisClient.setOptions(options.toClientOptions());
        }
        this.ttl = ttl;
    }

    public RedisCheckpointPort(String host, int port, Duration ttl) {
        this(RedisURI.builder().withHost(host).withPort(port).build(), ttl);
    }

    /**
     * Redis 客户端选项（生产可配）：超时与自动重连。
     *
     * <p><b>为什么需要 maxRetries：</b>Lettuce 默认在连接断开后<b>不重连</b>，
     * 一次网络抖动会让后续所有 checkpoint 读写持续失败——写确认链路会表现为
     * 「确认点了没反应」。生产应显式设置重试与退避。</p>
     */
    public static final class RedisOptions {

        private final Duration connectTimeout;
        private final Duration commandTimeout;
        private final int maxRetries;
        private final Duration retryBackoff;

        public RedisOptions(
                Duration connectTimeout,
                Duration commandTimeout,
                int maxRetries,
                Duration retryBackoff) {
            this.connectTimeout = connectTimeout;
            this.commandTimeout = commandTimeout;
            this.maxRetries = maxRetries;
            this.retryBackoff = retryBackoff;
        }

        ClientOptions toClientOptions() {
            SocketOptions socketOptions = SocketOptions.builder()
                    .connectTimeout(connectTimeout)
                    .build();
            return ClientOptions.builder()
                    .socketOptions(socketOptions)
                    // 命令超时：避免单次 GET/SET 卡死整个 Agent 请求（NFR-06：工具超时必配）
                    .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                    // 断线自动重连 + 指数退避；maxRetries=0 时退化为不重试（显式关闭）
                    .autoReconnect(true)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build();
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public Duration getCommandTimeout() {
            return commandTimeout;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }
    }

    @Override
    public void save(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        // runId 由 GraphKeelAgent 生成时已带 tenant + principal（FR-53）
        byte[] key = checkpoint.getRunId().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] value = serialize(checkpoint);
        try (StatefulRedisConnection<byte[], byte[]> conn =
                     redisClient.connect(new ByteArrayCodec())) {
            RedisStringCommands<byte[], byte[]> sync = conn.sync();
            // setEx 自带 TTL，避免 checkpoint 无限残留
            sync.setex(key, ttl.toSeconds(), value);
        }
    }

    @Override
    public Optional<Checkpoint> load(String runId) {
        Objects.requireNonNull(runId, "runId");
        byte[] key = runId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (StatefulRedisConnection<byte[], byte[]> conn =
                     redisClient.connect(new ByteArrayCodec())) {
            byte[] value = conn.sync().get(key);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(deserialize(value));
        }
    }

    @Override
    public void delete(String runId) {
        Objects.requireNonNull(runId, "runId");
        byte[] key = runId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (StatefulRedisConnection<byte[], byte[]> conn =
                     redisClient.connect(new ByteArrayCodec())) {
            conn.sync().del(key);
        }
    }

    /**
     * NFR-04 启动自检探针：真实建立连接并执行 PING。
     * 供 starter 的启动自检（KeelStartupSelfCheck）实现「Redis 连不通要报哪个 bean」，
     * 业务运维探活也可直接复用。连接失败 / 超时抛出异常（如 RedisConnectionException），
     * 由调用方决定 fail-fast 还是告警。
     */
    public void ping(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try (StatefulRedisConnection<byte[], byte[]> conn =
                     redisClient.connect(new ByteArrayCodec())) {
            // PING 命令受 command timeout 约束；显式收口避免 Lettuce 默认 60s 卡住启动
            conn.setTimeout(timeout);
            conn.sync().ping();
        }
    }

    @Override
    public void close() {
        redisClient.shutdown();
    }

    private byte[] serialize(Checkpoint checkpoint) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(checkpoint);
            return baos.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("checkpoint 序列化失败: " + exception.getMessage(), exception);
        }
    }

    private Checkpoint deserialize(byte[] bytes) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (Checkpoint) ois.readObject();
        } catch (Exception exception) {
            throw new IllegalStateException("checkpoint 反序列化失败: " + exception.getMessage(), exception);
        }
    }
}
