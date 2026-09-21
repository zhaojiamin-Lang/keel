package io.keel.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * FR-34 端到端测试：真实 MCP client（官方 SDK）经 SSE 传输接入参考服务器，
 * 验证 initialize / tools list / tools call（含受限写幂等）全链路走标准 MCP 协议。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IssueMcpServerApplicationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private InMemoryIssueServer issueServer;

    @Test
    void mcpClientConnectsOverSseAndCallsTools() {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                .builder("http://localhost:" + port)
                .sseEndpoint("/sse")
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .clientInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation(
                        "keel-harness-client", "0.1.0"))
                .build();
        try {
            InitializeResult init = client.initialize();
            assertThat(init.serverInfo().name()).isEqualTo(IssueMcpServerFactory.SERVER_NAME);

            ListToolsResult tools = client.listTools();
            assertThat(tools.tools())
                    .extracting(io.modelcontextprotocol.spec.McpSchema.Tool::name)
                    .containsExactly(
                            IssueToolRegistry.TOOL_SEARCH, IssueToolRegistry.TOOL_CREATE);

            // 受限写：带幂等键创建，重放同键不得产生第二次副作用
            CallToolResult created = client.callTool(new CallToolRequest(
                    IssueToolRegistry.TOOL_CREATE,
                    Map.of("idempotencyKey", "e2e-key-1", "title", "SSE 建单", "description", "e2e")));
            assertThat(created.isError()).isFalse();
            String firstId = issueServer.searchIssues("SSE 建单").get(0).id();

            client.callTool(new CallToolRequest(
                    IssueToolRegistry.TOOL_CREATE,
                    Map.of("idempotencyKey", "e2e-key-1", "title", "SSE 建单（重放）")));
            assertThat(issueServer.searchIssues("SSE 建单")).hasSize(1);
            assertThat(issueServer.searchIssues("SSE 建单").get(0).id()).isEqualTo(firstId);

            // 只读搜索经协议返回
            CallToolResult searched = client.callTool(new CallToolRequest(
                    IssueToolRegistry.TOOL_SEARCH, Map.of("keyword", "SSE")));
            assertThat(searched.isError()).isFalse();
            assertThat(((TextContent) searched.content().get(0)).text()).contains("1 issues");
        } finally {
            client.closeGracefully();
        }
    }
}
