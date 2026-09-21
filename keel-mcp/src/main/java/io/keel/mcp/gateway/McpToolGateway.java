package io.keel.mcp.gateway;

import java.util.Map;

import io.keel.core.KeelPrincipal;

/**
 * MCP 调用出口（可替换 / 可测试）：把一次工具调用发往 MCP server。
 * 传输异常以 RuntimeException 抛出，由上层决定重试。
 */
public interface McpToolGateway {

    GatewayResult callTool(KeelPrincipal principal, String toolName, Map<String, Object> arguments);

    /**
     * 带幂等键的调用（FR-32：写操作重试不得产生第二笔业务副作用）。
     * 实现应把 idempotencyKey 透传到 server 端做去重；为 null/blank 时
     * 等价于三参 {@link #callTool(KeelPrincipal, String, Map)}（只读路径不传键）。
     * 默认委托三参方法，既有实现与测试 Fake 零改动。
     */
    default GatewayResult callTool(
            KeelPrincipal principal, String toolName, Map<String, Object> arguments,
            String idempotencyKey) {
        return callTool(principal, toolName, arguments);
    }
}
