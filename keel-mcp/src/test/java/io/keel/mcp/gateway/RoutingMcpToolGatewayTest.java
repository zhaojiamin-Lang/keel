package io.keel.mcp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.keel.core.KeelPrincipal;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

@ExtendWith(MockitoExtension.class)
class RoutingMcpToolGatewayTest {

    @Mock
    private McpSyncClient gitlabClient;

    @Mock
    private McpSyncClient oaClient;

    private final KeelPrincipal principal = new KeelPrincipal("tenant", "subject", List.of());

    private RoutingMcpToolGateway gateway;

    @BeforeEach
    void setUp() {
        Map<String, String> index = Map.of(
                "gitlab.search_issues", "gitlab",
                "oa.list_approvals", "oa");
        gateway = new RoutingMcpToolGateway(
                Map.of("gitlab", gitlabClient, "oa", oaClient),
                new StaticMcpToolRouter(index));
    }

    @Test
    void routesToolToItsServer() {
        when(gitlabClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("3 条工单")), false));

        GatewayResult result = gateway.callTool(
                principal, "gitlab.search_issues", Map.of("project", "p-1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getText()).isEqualTo("3 条工单");

        ArgumentCaptor<McpSchema.CallToolRequest> captor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(gitlabClient).callTool(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("gitlab.search_issues");
        // 未命中该工具的 server 绝不被调用
        verify(oaClient, never()).callTool(any());
    }

    @Test
    void routesDifferentToolsToDifferentServers() {
        when(oaClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("无待办")), false));

        GatewayResult result = gateway.callTool(
                principal, "oa.list_approvals", Map.of());

        assertThat(result.isSuccess()).isTrue();
        verify(oaClient).callTool(any());
        verify(gitlabClient, never()).callTool(any());
    }

    @Test
    void unknownToolFailsClosedWithoutInvokingAnyServer() {
        GatewayResult result = gateway.callTool(
                principal, "unknown.tool", Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isBusinessError()).isTrue();
        assertThat(result.getText()).contains("未登记");

        // FR-35：绝不向任意 server 发起调用
        verify(gitlabClient, never()).callTool(any());
        verify(oaClient, never()).callTool(any());
    }

    @Test
    void emptyClientsRejected() {
        assertThatThrownBy(() -> new RoutingMcpToolGateway(
                Map.of(), new StaticMcpToolRouter(Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeReleasesAllClients() {
        gateway.close();
        Mockito.verify(gitlabClient).close();
        Mockito.verify(oaClient).close();
    }
}
