package io.keel.graph;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Critic / Guard 的通用裁决。{@code code} 为机器可读的拒绝原因
 * （如 MISSING_CITATION、WRITE_FORBIDDEN_BY_SKILL），{@code reason} 面向人。
 *
 * <p>三态语义：
 * <ul>
 *   <li>{@link #approved()}：放行；</li>
 *   <li>{@link #rejected(String, String)}：硬拒绝（如越权写操作）→ 终局 REJECTED；</li>
 *   <li>{@link #downgrade(String, String)}：信息不足降级（如缺引用）→ 终局 UNCERTAIN。</li>
 * </ul>
 * downgrade 与 rejected 都非 approved，但前者不视为「安全违规」，仅表示无法形成确定结论。
 */
public final class Verdict implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean approved;

    /** true 表示 downgrade（降级），false 表示 rejected（硬拒绝）；仅在 !approved 时有意义。 */
    private final boolean downgrade;

    private final String code;

    private final String reason;

    private Verdict(boolean approved, boolean downgrade, String code, String reason) {
        this.approved = approved;
        this.downgrade = downgrade;
        this.code = code == null ? "" : code;
        this.reason = reason == null ? "" : reason;
    }

    public static Verdict approved() {
        return new Verdict(true, false, "", "");
    }

    public static Verdict rejected(String code, String reason) {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("rejection code must not be blank");
        }
        return new Verdict(false, false, code, reason);
    }

    /**
     * 降级裁决：规则不通过但不属于硬拒绝，应把终局置为 UNCERTAIN 而非 REJECTED
     * （如 require-citation 结论缺引用，FR-42/FR-64）。
     */
    public static Verdict downgrade(String code, String reason) {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("downgrade code must not be blank");
        }
        return new Verdict(false, true, code, reason);
    }

    public boolean isApproved() {
        return approved;
    }

    public boolean isDowngrade() {
        return !approved && downgrade;
    }

    public String getCode() {
        return code;
    }

    public String getReason() {
        return reason;
    }
}
