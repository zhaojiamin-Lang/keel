package io.keel.mcp.gateway;

import java.util.Optional;

/**
 * 工具名 → MCP server 名 的路由策略（FR-31）。
 *
 * <p>把一次工具调用分发到正确的 MCP server。返回 {@link Optional#empty()} 表示该工具
 * 不属于任何已登记 server，上层必须 fail-closed 直接拒绝，绝不做「就近猜一个 server」
 * 之类的兜底（FR-35）。</p>
 */
public interface McpToolRouter {

    /** 返回工具归属的 server 名；未知工具返回 empty。 */
    Optional<String> route(String toolName);
}
