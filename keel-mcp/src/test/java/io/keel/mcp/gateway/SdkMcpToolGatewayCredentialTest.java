package io.keel.mcp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.keel.core.KeelPrincipal;
import io.keel.mcp.auth.CredentialProvider;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

class SdkMcpToolGatewayCredentialTest {

    private McpSyncClient mcpClient = mock(McpSyncClient.class);

    @Test
    void credentialRequiredAndResolvedAllowsCall() {
        // server 有凭证要求，principal 能解析到凭证 → 放行
        CredentialProvider provider = new CredentialProvider() {
            @Override
            public Optional<String> resolveCredential(KeelPrincipal p, String server) {
                return "gitlab".equals(server) ? Optional.of("token-123") : Optional.empty();
            }
            @Override
            public boolean isCredentialRequired(String server) {
                return "gitlab".equals(server);
            }
        };
        when(mcpClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("OK")), false));

        SdkMcpToolGateway gateway = new SdkMcpToolGateway(mcpClient, "gitlab", provider);
        KeelPrincipal principal = new KeelPrincipal("t", "u", List.of("gitlab"));

        GatewayResult result = gateway.callTool(principal, "search_issues", Map.of());

        assertThat(result.isSuccess()).isTrue();
        verify(mcpClient).callTool(any());
    }

    @Test
    void credentialRequiredButNotResolvedFailsClosed() {
        // server 有凭证要求，principal 无法解析到凭证 → fail-closed 拒绝
        CredentialProvider provider = new CredentialProvider() {
            @Override
            public Optional<String> resolveCredential(KeelPrincipal p, String server) {
                // principal 的 resourceIds 不含 server → 返回 empty
                return Optional.empty();
            }
            @Override
            public boolean isCredentialRequired(String server) {
                return "gitlab".equals(server);
            }
        };

        SdkMcpToolGateway gateway = new SdkMcpToolGateway(mcpClient, "gitlab", provider);
        KeelPrincipal principal = new KeelPrincipal("t", "u", List.of()); // 无 resourceIds

        GatewayResult result = gateway.callTool(principal, "search_issues", Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getText()).contains("凭证校验失败");
        // 确认没有向 MCP server 发起调用
        verify(mcpClient, never()).callTool(any());
    }

    @Test
    void noCredentialRequirementAllowsCall() {
        // server 无凭证要求 → 放行（向后兼容）
        CredentialProvider provider = CredentialProvider.ALLOW_ALL;
        when(mcpClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("OK")), false));

        SdkMcpToolGateway gateway = new SdkMcpToolGateway(mcpClient, "default", provider);
        KeelPrincipal principal = new KeelPrincipal("t", "u", List.of());

        GatewayResult result = gateway.callTool(principal, "search_orders", Map.of());

        assertThat(result.isSuccess()).isTrue();
        verify(mcpClient).callTool(any());
    }
}