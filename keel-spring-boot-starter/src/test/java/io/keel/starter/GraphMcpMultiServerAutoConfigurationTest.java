package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.KeelPrincipal;
import io.keel.mcp.gateway.McpToolGateway;
import io.keel.mcp.gateway.RoutingMcpToolGateway;
import io.keel.mcp.gateway.StaticMcpToolRouter;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * 多 MCP server 路由端到端测试（FR-31/FR-35）：业务提供 {@link RoutingMcpToolGateway}
 * （mock 两个后端 client，不触网），验证 ①CALL_TOOL 精确路由到对应 server
 * ②未被命中的 server 绝不被调用。
 */
@SpringBootTest(properties = {
        "keel.agent.mode=graph",
        "keel.tools.allowed[0]=gitlab.search_issues",
        "keel.tools.allowed[1]=oa.list_approvals",
        "keel.tools.allowed[2]=issue.create",
        "keel.tools.allowed[3]=search_orders"
})
class GraphMcpMultiServerAutoConfigurationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private GraphRagTestFixtures.QueuedChatModel chatModel;

    @Autowired
    private McpSyncClient gitlabClient;

    @Autowired
    private McpSyncClient oaClient;

    @BeforeEach
    void resetClients() {
        reset(gitlabClient, oaClient);
    }

    @Test
    void toolRoutesToItsOwnServer() {
        when(gitlabClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("GitLab 工单 #42")), false));
        chatModel.reset(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"gitlab.search_issues\","
                        + "\"arguments\":{\"project\":\"p-1\"}}",
                "{\"action\":\"ANSWER\",\"answer\":\"GitLab 工单 #42 已找到\"}",
                "{\"verdict\":\"APPROVED\"}");

        AgentResult result = keelAgent.run(request("查一下 GitLab 工单"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("GitLab 工单 #42");

        ArgumentCaptor<McpSchema.CallToolRequest> captor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(gitlabClient).callTool(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("gitlab.search_issues");
        // 路由精确命中 gitlab，oa 后端绝不被调用
        verify(oaClient, never()).callTool(any());
    }

    private AgentRequest request(String input) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .skill("mcp-multi")
                .input(input)
                .build();
    }

    @SpringBootApplication
    static class MultiMcpTestApplication {

        @Bean
        ChatModel chatModel() {
            return new GraphRagTestFixtures.QueuedChatModel();
        }

        @Bean
        McpSyncClient gitlabClient() {
            return Mockito.mock(McpSyncClient.class);
        }

        @Bean
        McpSyncClient oaClient() {
            return Mockito.mock(McpSyncClient.class);
        }

        /**
         * 业务自定义路由网关：mock 两个后端，index 决定 tool → server 归属。
         * 通过 @ConditionalOnMissingBean 覆盖 starter 默认的单 server 网关。
         */
        @Bean
        McpToolGateway routingGateway(McpSyncClient gitlabClient, McpSyncClient oaClient) {
            Map<String, String> index = Map.of(
                    "gitlab.search_issues", "gitlab",
                    "oa.list_approvals", "oa");
            return new RoutingMcpToolGateway(
                    Map.of("gitlab", gitlabClient, "oa", oaClient),
                    new StaticMcpToolRouter(index));
        }
    }
}
