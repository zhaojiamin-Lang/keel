package io.keel.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 幂等键格式的契约测试（FR-32 P0）。
 *
 * <p>键格式被三处依赖（client 生成 / GuardNode 校验 / ToolPort 透传），
 * 且它是「同一会话重复提交只产生一次副作用」的语义载体。这里把格式与语义
 * 锁定成断言——任何人改动格式都会让本测试红，而不是等到线上重试保护失效。</p>
 */
class IdempotencyKeysTest {

    @Test
    void keyFormatIsPrefixColonTenantColonSession() {
        String key = IdempotencyKeys.of("keel", "tenant-A", "sess-1");

        assertThat(key).isEqualTo("keel:tenant-A:sess-1");
    }

    @Test
    void sameSessionProducesSameKey() {
        // 核心语义：同一会话共用一个键，重复提交才能被服务端 dedupe 掉
        String first = IdempotencyKeys.of("keel", "tenant-A", "sess-1");
        String second = IdempotencyKeys.of("keel", "tenant-A", "sess-1");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentTenantOrSessionProducesDifferentKey() {
        // 跨租户绝不能共用键，否则一个租户的提交会 dedupe 掉另一个租户的
        assertThat(IdempotencyKeys.of("keel", "tenant-A", "sess-1"))
                .isNotEqualTo(IdempotencyKeys.of("keel", "tenant-B", "sess-1"));
        assertThat(IdempotencyKeys.of("keel", "tenant-A", "sess-1"))
                .isNotEqualTo(IdempotencyKeys.of("keel", "tenant-A", "sess-2"));
    }

    @Test
    void blankPrefixFallsBackToDefault() {
        assertThat(IdempotencyKeys.of("  ", "tenant-A", "sess-1"))
                .isEqualTo(IdempotencyKeys.of("keel", "tenant-A", "sess-1"));
        assertThat(IdempotencyKeys.of(null, "tenant-A", "sess-1"))
                .isEqualTo("keel:tenant-A:sess-1");
    }

    @Test
    void twoArgOverloadUsesDefaultPrefix() {
        // 两参重载必须与「显式传默认前缀」等价，否则示例 Controller 的历史行为会漂移
        assertThat(IdempotencyKeys.of("tenant-A", "sess-1"))
                .isEqualTo(IdempotencyKeys.of(IdempotencyKeys.DEFAULT_PREFIX, "tenant-A", "sess-1"));
    }

    @Test
    void nullTenantOrSessionIsRejected() {
        // 缺租户 → 键无法隔离；缺 session → 无法 dedupe。都必须在生成期就报错，
        // 而不是生成一个看似合法却失去保护意义的键
        assertThatThrownBy(() -> IdempotencyKeys.of("keel", null, "sess-1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> IdempotencyKeys.of("keel", "tenant-A", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId");
    }
}
