package io.keel.mcp.server;

import java.util.List;

import io.modelcontextprotocol.server.McpServer;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * FR-34：把传输层（stdio / sse）+ 工具注册表组装成 MCP 官方 SDK 的
 * {@link McpSyncServer}。
 *
 * <p>传输由调用方决定（与协议解耦，不自研 MCP 协议）：</p>
 * <ul>
 *   <li>stdio：{@code new StdioServerTransportProvider(objectMapper)} —— 供本地进程接入；</li>
 *   <li>sse：{@code HttpServletSseServerTransportProvider.builder()...build()} —— 供远程 HTTP 接入。</li>
 * </ul>
 */
public final class IssueMcpServerFactory {

    public static final String SERVER_NAME = "keel-issue-server";
    public static final String SERVER_VERSION = "0.1.0";

    private IssueMcpServerFactory() {
    }

    public static McpSyncServer create(
            io.modelcontextprotocol.spec.McpServerTransportProvider transportProvider,
            IssueToolRegistry registry) {
        List<SyncToolSpecification> tools = McpIssueTools.create(registry);
        return McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                // 只暴露 tools 能力：参考实现不提供 resources/prompts
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(tools)
                .build();
    }
}
