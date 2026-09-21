package io.keel.core;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

public final class AgentRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final KeelPrincipal principal;
    private final String skill;
    private final String input;
    private final String sessionId;
    private final String idempotencyKey;
    /**
     * 用户确认待执行写动作的 actionId（FR-13/FR-32）。
     * 非空时，图从 checkpoint 恢复并执行对应 PendingAction 的写工具；
     * 为空表示普通请求，写工具仅产生 PendingAction 不执行。
     */
    private final String confirmedActionId;

    private AgentRequest(Builder builder) {
        this.principal = builder.principal;
        this.skill = builder.skill;
        this.input = builder.input;
        this.sessionId = builder.sessionId;
        this.idempotencyKey = builder.idempotencyKey;
        this.confirmedActionId = builder.confirmedActionId;
    }

    /**
     * 反序列化复验：Java 反序列化不经过 Builder（checkpoint 持久化恢复、缓存等场景），
     * 在此复验与 {@code Builder.build()} 相同的不变量，防止损坏或被篡改的序列化流
     * 构造出 principal=null 的请求对象——下游 {@code getPrincipal()} 调用点会 NPE。
     */
    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (principal == null) {
            throw new InvalidObjectException("principal");
        }
        if (input == null) {
            throw new InvalidObjectException("input");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public KeelPrincipal getPrincipal() {
        return principal;
    }

    public String getSkill() {
        return skill;
    }

    public String getInput() {
        return input;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getConfirmedActionId() {
        return confirmedActionId;
    }

    public static final class Builder {

        private KeelPrincipal principal;
        private String skill;
        private String input;
        private String sessionId;
        private String idempotencyKey;
        private String confirmedActionId;

        private Builder() {
        }

        public Builder principal(KeelPrincipal principal) {
            this.principal = principal;
            return this;
        }

        public Builder skill(String skill) {
            this.skill = skill;
            return this;
        }

        public Builder input(String input) {
            this.input = input;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder confirmedActionId(String confirmedActionId) {
            this.confirmedActionId = confirmedActionId;
            return this;
        }

        public AgentRequest build() {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(input, "input");
            return new AgentRequest(this);
        }
    }
}
