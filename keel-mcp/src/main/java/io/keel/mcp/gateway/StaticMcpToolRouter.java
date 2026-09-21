package io.keel.mcp.gateway;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于静态映射（toolName → serverName）的路由实现。
 *
 * <p>映射在装配时由 {@code McpClientFactory.buildToolServerIndex} 生成（显式配置的
 * tools 优先，缺省用 {@code listTools()} 自动发现），本类只做不可变的只读查询。
 * 工具归属唯一性（同一工具登记到多个 server）在生成索引阶段 fail-fast，不在此重复校验。</p>
 */
public final class StaticMcpToolRouter implements McpToolRouter {

    private final Map<String, String> toolToServer;

    public StaticMcpToolRouter(Map<String, String> toolToServer) {
        Objects.requireNonNull(toolToServer, "toolToServer");
        this.toolToServer = Map.copyOf(toolToServer);
    }

    @Override
    public Optional<String> route(String toolName) {
        return Optional.ofNullable(toolToServer.get(toolName));
    }
}
