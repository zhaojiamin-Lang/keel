package io.keel.graph.spi;

import io.keel.core.KeelPrincipal;
import io.keel.graph.ToolCall;
import io.keel.graph.ToolObservation;

/**
 * 工具调用端口（后续由 keel-mcp 适配：统一鉴权、超时、重试、幂等、脱敏）。
 *
 * <p>{@link #isWriteTool} 是写身份的唯一权威来源——模型自称「只读」不算数，
 * 图内核据此保证写工具在确认前绝不执行（FR-13）。</p>
 */
public interface ToolPort {

    ToolObservation invoke(ToolCall call, KeelPrincipal principal);

    /**
     * 确认后的写工具执行路径（FR-13/FR-31/FR-32）。
     *
     * <p>仅由图内核在「用户已确认 pendingAction」（{@code state.isWriteConfirmed()}）时调用。
     * 默认委托 {@link #invoke}，既有实现（业务自建 ToolPort）零改动即可保持原行为；
     * 需要在确认后放开写限制的实现（如 keel-mcp 的 {@code McpToolPort}）覆写本方法。
     * 实现应把 {@link ToolCall#getIdempotencyKey() idempotencyKey} 透传到 server 端做去重，
     * 保证重试不产生第二笔业务副作用（FR-32）。</p>
     */
    default ToolObservation invokeConfirmedWrite(ToolCall call, KeelPrincipal principal) {
        return invoke(call, principal);
    }

    boolean isWriteTool(String tool);
}
