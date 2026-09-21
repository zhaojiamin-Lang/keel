package io.keel.core;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * 记忆注入 Loop 的预算（FR-54）：最多召回条数与单条最长字符。
 * 超长截断策略由注入方按本预算执行，避免记忆把模型上下文撑爆。
 */
public final class MemoryBudget implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int maxFragments;
    private final int maxCharsPerFragment;

    public MemoryBudget(int maxFragments, int maxCharsPerFragment) {
        if (maxFragments <= 0) {
            throw new IllegalArgumentException("maxFragments 必须为正: " + maxFragments);
        }
        if (maxCharsPerFragment <= 0) {
            throw new IllegalArgumentException("maxCharsPerFragment 必须为正: " + maxCharsPerFragment);
        }
        this.maxFragments = maxFragments;
        this.maxCharsPerFragment = maxCharsPerFragment;
    }

    public static MemoryBudget defaults() {
        return new MemoryBudget(5, 200);
    }

    public int getMaxFragments() {
        return maxFragments;
    }

    public int getMaxCharsPerFragment() {
        return maxCharsPerFragment;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MemoryBudget other)) {
            return false;
        }
        return maxFragments == other.maxFragments
                && maxCharsPerFragment == other.maxCharsPerFragment;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxFragments, maxCharsPerFragment);
    }
}
