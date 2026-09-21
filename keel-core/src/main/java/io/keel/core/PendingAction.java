package io.keel.core;

import java.io.Serial;
import java.io.Serializable;

public final class PendingAction implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String actionId;
    private final String tool;
    private final String payloadJson;
    private final boolean confirmed;
    /**
     * 写动作的幂等键（FR-32/FR-72）。
     * <p>来源为请求级 {@link AgentRequest#getIdempotencyKey()}，由 PlanNode/ToolNode
     * 构造 PendingAction 时透传。Harness 据此判定「写操作无幂等」是否成立——
     * expectWrite=true 但 idempotencyKey 缺失即标红。</p>
     */
    private final String idempotencyKey;

    public PendingAction(String actionId, String tool, String payloadJson) {
        this(actionId, tool, payloadJson, false, null);
    }

    public PendingAction(String actionId, String tool, String payloadJson, boolean confirmed) {
        this(actionId, tool, payloadJson, confirmed, null);
    }

    public PendingAction(
            String actionId,
            String tool,
            String payloadJson,
            boolean confirmed,
            String idempotencyKey) {
        this.actionId = actionId;
        this.tool = tool;
        this.payloadJson = payloadJson;
        this.confirmed = confirmed;
        this.idempotencyKey = idempotencyKey;
    }

    public String getActionId() {
        return actionId;
    }

    public String getTool() {
        return tool;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
