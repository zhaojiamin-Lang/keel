package io.keel.graph;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;

/**
 * Loop 预算（FR-11 / FR-63）：步数 / 时长 / 写次数 三档预算。
 * 超过任一预算，图必须停止并返回可解释的 errorCode，而不是无限重试。
 */
public final class StepBudget implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_MAX_ITERATIONS = 5;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    /** FR-63：默认最多 3 次写动作——建单 + 改状态 + 补充，覆盖常见多步写场景。 */
    public static final int DEFAULT_MAX_WRITES = 3;

    private final int maxIterations;
    private final Duration timeout;
    private final int maxWrites;

    /** 向后兼容：不显式配写次数预算时，使用 {@link #DEFAULT_MAX_WRITES}。 */
    public StepBudget(int maxIterations, Duration timeout) {
        this(maxIterations, timeout, DEFAULT_MAX_WRITES);
    }

    public StepBudget(int maxIterations, Duration timeout, int maxWrites) {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        this.maxIterations = maxIterations;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxWrites <= 0) {
            throw new IllegalArgumentException("maxWrites must be positive");
        }
        this.maxWrites = maxWrites;
    }

    public static StepBudget defaults() {
        return new StepBudget(DEFAULT_MAX_ITERATIONS, DEFAULT_TIMEOUT, DEFAULT_MAX_WRITES);
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public int getMaxWrites() {
        return maxWrites;
    }
}