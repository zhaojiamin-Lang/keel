package io.keel.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;


import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

/**
 * stdio 传输参考入口（FR-34）：本地子进程方式接入（如 Claude Desktop 的
 * mcpServers 配置）。
 *
 * <p>用法：{@code java -jar keel-mcp-server.jar --stdio}（或直接运行主类）。
 * 协议帧走 stdin/stdout，日志严禁写 stdout。</p>
 */
public final class StdioIssueServerMain {

    private StdioIssueServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        StdioServerTransportProvider transportProvider =
                new StdioServerTransportProvider(new ObjectMapper());
        IssueMcpServerFactory.create(
                transportProvider, new IssueToolRegistry(new InMemoryIssueServer()));
        // MCP 会话由 SDK 线程在 stdin 上驱动；主线程挂起保持进程存活
        Thread.currentThread().join();
    }
}
