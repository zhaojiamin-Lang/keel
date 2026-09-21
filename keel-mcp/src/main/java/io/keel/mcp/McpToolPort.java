package io.keel.mcp;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.keel.core.KeelPrincipal;
import io.keel.graph.ToolCall;
import io.keel.graph.ToolObservation;
import io.keel.graph.spi.ToolPort;
import io.keel.mcp.gateway.GatewayResult;
import io.keel.mcp.gateway.McpToolGateway;

/**
 * keel-graph {@link ToolPort} 的 MCP 实现（FR-31~FR-33）。
 * <ol>
 *   <li>写身份判定在代码里完成（本地只读注册表），未登记工具 fail-closed，默认拒绝；</li>
 *   <li>参数 JSON 非法直接返回失败观察，不触达 MCP server；</li>
 *   <li>传输类异常按预算重试；业务错误（isError / JSON-RPC error）不重试；</li>
 *   <li>返回文本先脱敏再进图状态，防止敏感数据进入模型上下文；</li>
 *   <li>确认后的写（{@link #invokeConfirmedWrite}）放开写限制并透传幂等键到
 *       server 端去重（FR-32）——未确认路径 {@link #invoke} 对写工具仍然拒绝（FR-13）。</li>
 * </ol>
 */
public class McpToolPort implements ToolPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final McpToolGateway gateway;

    private final ToolWriteClassifier writeClassifier;

    private final ContentRedactor contentRedactor;

    private final int maxRetries;

    private final long retryBackoffMillis;

    public McpToolPort(McpToolGateway gateway, McpToolOptions options) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(options, "options");
        this.writeClassifier = new ToolWriteClassifier(options.readOnlyTools());
        this.contentRedactor = options.contentRedactor() != null
                ? options.contentRedactor() : ContentRedactor.defaultRules();
        this.maxRetries = Math.max(0, options.maxRetries());
        this.retryBackoffMillis = Math.max(0L, options.retryBackoffMillis());
    }

    @Override
    public ToolObservation invoke(ToolCall call, KeelPrincipal principal) {
        Objects.requireNonNull(call, "call");
        // Guard 代码化：写身份只信本地注册表，未登记的工具一律拒绝，绝不发起调用（FR-32）。
        // 确认后的写走 invokeConfirmedWrite，此处永不放行。
        if (writeClassifier.isWriteTool(call.getTool())) {
            return ToolObservation.failed(call.getTool(),
                    "工具未登记为只读，按写操作 fail-closed 拒绝: " + call.getTool());
        }
        return execute(call, principal, null);
    }

    /**
     * 确认后的写执行（FR-13/FR-31/FR-32）：仅由图内核在用户确认 pendingAction 后调用。
     * 放开写限制，幂等键随调用透传到 server 端做去重，重试不产生第二笔副作用。
     */
    @Override
    public ToolObservation invokeConfirmedWrite(ToolCall call, KeelPrincipal principal) {
        Objects.requireNonNull(call, "call");
        return execute(call, principal, call.getIdempotencyKey());
    }

    /** 读 / 确认写共用的执行链：解析参数 → 按预算重试 → 脱敏。 */
    private ToolObservation execute(ToolCall call, KeelPrincipal principal, String idempotencyKey) {
        String toolName = call.getTool();
        String rawArguments = call.getArgumentsJson();

        Map<String, Object> arguments;
        if (rawArguments == null || rawArguments.isBlank()) {
            arguments = Map.of();
        } else {
            try {
                arguments = OBJECT_MAPPER.readValue(rawArguments, Map.class);
            } catch (JsonProcessingException e) {
                return ToolObservation.failed(toolName,
                        "工具参数不是合法 JSON 对象: " + e.getOriginalMessage());
            }
        }

        GatewayResult result = invokeWithRetry(principal, toolName, arguments, idempotencyKey);
        if (!result.isSuccess()) {
            return ToolObservation.failed(toolName, result.getText());
        }
        // 脱敏在进入图状态之前完成（FR-33）
        return ToolObservation.ok(toolName, contentRedactor.redact(result.getText()));
    }

    @Override
    public boolean isWriteTool(String toolName) {
        return writeClassifier.isWriteTool(toolName);
    }

    private GatewayResult invokeWithRetry(KeelPrincipal principal, String toolName,
            Map<String, Object> arguments, String idempotencyKey) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // FR-32：幂等键随调用透传，传输类重试由 server 按键去重；
                // 无键（只读路径）显式走三参，语义与四参默认实现等价
                GatewayResult result = (idempotencyKey == null || idempotencyKey.isBlank())
                        ? gateway.callTool(principal, toolName, arguments)
                        : gateway.callTool(principal, toolName, arguments, idempotencyKey);
                // 成功或业务错误都是确定性结果，立即返回不重试
                if (result.isSuccess() || result.isBusinessError()) {
                    return result;
                }
                lastFailure = new RuntimeException(result.getText());
            } catch (RuntimeException e) {
                // 传输类故障（连接断开 / 超时 / SDK 抛错）：进入退避重试
                lastFailure = e;
            }
            if (attempt < maxRetries) {
                sleepBackoff();
            }
        }
        return GatewayResult.transportError("MCP 调用重试预算耗尽: "
                + (lastFailure == null ? toolName : lastFailure.getMessage()));
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(retryBackoffMillis);
        } catch (InterruptedException e) {
            // 恢复中断状态并立即结束重试，避免吞掉中断信号
            Thread.currentThread().interrupt();
        }
    }
}
