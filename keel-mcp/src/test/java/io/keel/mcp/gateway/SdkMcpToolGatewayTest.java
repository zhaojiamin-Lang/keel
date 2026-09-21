package io.keel.mcp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.keel.core.KeelPrincipal;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

@ExtendWith(MockitoExtension.class)
class SdkMcpToolGatewayTest {

    @Mock
    private McpSyncClient mcpClient;

    private SdkMcpToolGateway gateway;

    private final KeelPrincipal principal = new KeelPrincipal("tenant", "subject", List.of());

    @BeforeEach
    void setUp() {
        gateway = new SdkMcpToolGateway(mcpClient);
    }

    @Test
    void textContentsAreJoinedWithNewline() {
        when(mcpClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("第一段结果"),
                        new McpSchema.TextContent("第二段结果")),
                false));

        GatewayResult result = gateway.callTool(principal, "search_orders", Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getText()).isEqualTo("第一段结果\n第二段结果");
    }

    @Test
    void toolNameAndArgumentsArePassedToSdkRequest() {
        when(mcpClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("已发货")), false));

        gateway.callTool(principal, "search_orders", Map.of("orderId", "A-1"));

        ArgumentCaptor<McpSchema.CallToolRequest> captor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        org.mockito.Mockito.verify(mcpClient).callTool(captor.capture());
        McpSchema.CallToolRequest request = captor.getValue();
        assertThat(request.name()).isEqualTo("search_orders");
        assertThat(request.arguments()).containsEntry("orderId", "A-1");
    }

    @Test
    void mcpIsErrorTrueMapsToBusinessError() {
        when(mcpClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("服务端拒绝：无权限")), true));

        GatewayResult result = gateway.callTool(principal, "search_orders", Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isBusinessError()).isTrue();
        assertThat(result.getText()).contains("无权限");
    }

    @Test
    void nonTextContentIsOmittedAndPlaceholderWhenNoText() {
        McpSchema.ImageContent image = new McpSchema.ImageContent(
                null, null, "aHVrZQ==", "image/png");
        when(mcpClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(image), false));

        GatewayResult result = gateway.callTool(principal, "render_qr", Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getText()).isEqualTo("（工具未返回文本内容）");
    }

    @Test
    void idempotencyKeyIsMergedIntoArgumentsAsReservedField() {
        // FR-32：幂等键以保留参数透传给 server 端做去重，原始业务参数保持不变
        when(mcpClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("已创建")), false));

        Map<String, Object> arguments = Map.of("title", "子任务");
        gateway.callTool(principal, "issue.create", arguments, "idem-9");

        ArgumentCaptor<McpSchema.CallToolRequest> captor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        org.mockito.Mockito.verify(mcpClient).callTool(captor.capture());
        McpSchema.CallToolRequest request = captor.getValue();
        assertThat(request.arguments())
                .containsEntry("title", "子任务")
                .containsEntry(SdkMcpToolGateway.IDEMPOTENCY_KEY_ARG, "idem-9");
    }

    @Test
    void nullIdempotencyKeyDoesNotInjectReservedField() {
        // 只读路径不传键：参数里不得出现保留字段
        when(mcpClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("ok")), false));

        gateway.callTool(principal, "search_orders", Map.of("orderId", "A-1"), null);

        ArgumentCaptor<McpSchema.CallToolRequest> captor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        org.mockito.Mockito.verify(mcpClient).callTool(captor.capture());
        assertThat(captor.getValue().arguments())
                .containsEntry("orderId", "A-1")
                .doesNotContainKey(SdkMcpToolGateway.IDEMPOTENCY_KEY_ARG);
    }
}
