package io.keel.core;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 写动作的 Outbox 记录（NFR-03 / 需求 §2.2 / §7.2）。
 *
 * <p>确认执行的写工具真正运行后，图内核通过 {@code OutboxPort} 把本记录交给业务，
 * 业务在自己的本地事务里落 outbox 表——Keel 只提供钩子与上下文，
 * 不建表、不接管业务事务（§2.2 非目标：不替代业务方的分库分表方案）。</p>
 *
 * <p>{@code idempotencyKey} 是业务侧防重的关键：同一写动作的幂等跳过不会产生
 * 本记录（见 {@code ToolNode}），业务可据此保证「重放 10 次、副作用 1 笔」（FR-32）。</p>
 */
public final class OutboxRecord implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String tenantId;
    private final String subjectId;
    private final String sessionId;
    private final String skill;
    private final String actionId;
    private final String tool;
    private final String idempotencyKey;
    private final String payloadJson;
    private final boolean success;
    private final String observationSummary;
    private final Instant createdAt;

    private OutboxRecord(Builder builder) {
        this.tenantId = builder.tenantId;
        this.subjectId = builder.subjectId;
        this.sessionId = builder.sessionId;
        this.skill = builder.skill;
        this.actionId = builder.actionId;
        this.tool = builder.tool;
        this.idempotencyKey = builder.idempotencyKey;
        this.payloadJson = builder.payloadJson;
        this.success = builder.success;
        this.observationSummary = builder.observationSummary;
        this.createdAt = builder.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSkill() {
        return skill;
    }

    public String getActionId() {
        return actionId;
    }

    public String getTool() {
        return tool;
    }

    /** 写动作幂等键（FR-32），业务侧据此防重。 */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    /** 写工具是否执行成功（失败也会产生记录，由业务决定是否落 outbox / 告警）。 */
    public boolean isSuccess() {
        return success;
    }

    /** 工具返回摘要或失败原因，已截断，供业务排查用，不作为业务状态。 */
    public String getObservationSummary() {
        return observationSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static final class Builder {

        private String tenantId;
        private String subjectId;
        private String sessionId;
        private String skill;
        private String actionId;
        private String tool;
        private String idempotencyKey;
        private String payloadJson;
        private boolean success;
        private String observationSummary = "";
        private Instant createdAt = Instant.now();

        private Builder() {
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder subjectId(String subjectId) {
            this.subjectId = subjectId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder skill(String skill) {
            this.skill = skill;
            return this;
        }

        public Builder actionId(String actionId) {
            this.actionId = actionId;
            return this;
        }

        public Builder tool(String tool) {
            this.tool = tool;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder payloadJson(String payloadJson) {
            this.payloadJson = payloadJson;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder observationSummary(String observationSummary) {
            this.observationSummary = observationSummary;
            return this;
        }

        // 注意：createdAt 故意不提供 Builder 方法——它是内核生成的审计时间戳
        // （默认 Instant.now()），开放覆盖会让业务/测试可以伪造写入时间。

        public OutboxRecord build() {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(tool, "tool");
            return new OutboxRecord(this);
        }
    }
}
