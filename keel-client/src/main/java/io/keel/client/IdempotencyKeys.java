package io.keel.client;

import java.util.Objects;

/**
 * 请求级幂等键生成（FR-32 P0：写操作必须带幂等键，重试不得产生第二笔业务副作用）。
 *
 * <p><b>为什么单独成类：</b>键的格式是安全语义的一部分，被三处依赖——</p>
 * <ol>
 *     <li>{@link KeelAgentClient} 为每次请求生成键；</li>
 *     <li>{@code GuardNode.codeRules} 检查键非空，缺失即 fail-closed 报
 *         {@code WRITE_WITHOUT_IDEMPOTENCY}；</li>
 *     <li>{@code ToolPort} 实现把它透传给业务服务做 server 端 dedupe。</li>
 * </ol>
 * <p>三者必须对格式有同一认知，否则「读得对但拒绝得莫名」。收口到本类后，
 * 数据集与测试可直接引用同一常量，避免格式在两处各写一遍后漂移。</p>
 *
 * <p><b>语义（刻意保持稳定）：</b>键 = {@code keel:{tenantId}:{sessionId}}——
 * <b>同一会话内所有请求共用一个键</b>。这是为了「同一会话重复提交只产生一次副作用」
 * 的语义：用户在同一会话里连点两次「确认」，第二次因为服务端已有该键的 dedupe 记录
 * 而不会二次写入。若改成「每请求新键」，重试保护就失效了——所以不要随手改。</p>
 */
public final class IdempotencyKeys {

    /** 键前缀，可通过 {@code keel.client.idempotency-key-prefix} 覆盖。 */
    public static final String DEFAULT_PREFIX = "keel";

    private IdempotencyKeys() {
    }

    /**
     * 生成请求级幂等键。
     *
     * @param prefix    键前缀，为空时回退 {@link #DEFAULT_PREFIX}
     * @param tenantId  租户，参与键名保证跨租户不互相 dedupe
     * @param sessionId 会话，同一会话共用一个键（见类注释的语义说明）
     * @return 形如 {@code keel:tenant-A:sess-1} 的键
     */
    public static String of(String prefix, String tenantId, String sessionId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空（幂等键必须按租户隔离）");
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        String effectivePrefix = (prefix == null || prefix.isBlank()) ? DEFAULT_PREFIX : prefix;
        return effectivePrefix + ":" + tenantId + ":" + sessionId;
    }

    /** 使用默认前缀生成（行为与示例 Controller 的历史写法一致）。 */
    public static String of(String tenantId, String sessionId) {
        return of(DEFAULT_PREFIX, tenantId, sessionId);
    }
}
