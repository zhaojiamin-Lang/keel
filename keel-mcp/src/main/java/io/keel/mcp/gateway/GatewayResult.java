package io.keel.mcp.gateway;

/**
 * MCP 网关调用结果（三态）。
 * <ul>
 *   <li>成功：文本可进入图状态（仍需脱敏）；</li>
 *   <li>业务错误：MCP isError=true 或 JSON-RPC 错误，确定性失败，不重试；</li>
 *   <li>传输错误：连接断开 / 超时等，调用方可按预算重试。</li>
 * </ul>
 */
public final class GatewayResult {

    private final boolean success;

    private final boolean businessError;

    private final String text;

    private GatewayResult(boolean success, boolean businessError, String text) {
        this.success = success;
        this.businessError = businessError;
        this.text = text;
    }

    public static GatewayResult ok(String text) {
        return new GatewayResult(true, false, text);
    }

    public static GatewayResult businessError(String message) {
        return new GatewayResult(false, true, message);
    }

    public static GatewayResult transportError(String message) {
        return new GatewayResult(false, false, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isBusinessError() {
        return businessError;
    }

    public String getText() {
        return text;
    }
}
