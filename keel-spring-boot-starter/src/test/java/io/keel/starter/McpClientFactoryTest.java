package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * {@link McpClientFactory} 配置校验与纯构造测试：
 * 非法配置快速失败；合法配置只构造 transport，不触网、不拉起子进程。
 */
@ExtendWith(MockitoExtension.class)
class McpClientFactoryTest {

    @Mock
    private McpSyncClient gitlabClient;

    @Mock
    private McpSyncClient oaClient;

    @Test
    void blankTransportFailsFast() {
        assertThatThrownBy(() -> McpClientFactory.buildTransport(new KeelProperties.Tools.Mcp()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transport");
    }

    @Test
    void unknownTransportFailsFast() {
        KeelProperties.Tools.Mcp config = new KeelProperties.Tools.Mcp();
        config.setTransport("websocket");

        assertThatThrownBy(() -> McpClientFactory.buildTransport(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("websocket");
    }

    @Test
    void sseWithoutBaseUrlFailsFast() {
        KeelProperties.Tools.Mcp config = new KeelProperties.Tools.Mcp();
        config.setTransport(KeelProperties.Tools.TRANSPORT_SSE);

        assertThatThrownBy(() -> McpClientFactory.buildTransport(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    void stdioWithoutCommandFailsFast() {
        KeelProperties.Tools.Mcp config = new KeelProperties.Tools.Mcp();
        config.setTransport(KeelProperties.Tools.TRANSPORT_STDIO);

        assertThatThrownBy(() -> McpClientFactory.buildTransport(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }

    @Test
    void validSseConfigBuildsTransportWithoutNetwork() {
        KeelProperties.Tools.Mcp config = new KeelProperties.Tools.Mcp();
        config.setTransport(KeelProperties.Tools.TRANSPORT_SSE);
        config.setBaseUrl("http://127.0.0.1:1");
        config.setSseEndpoint("/sse");
        config.setToken("secret-token");

        // build() 只组装 HttpClient，不发起任何连接
        McpClientTransport transport = McpClientFactory.buildTransport(config);

        assertThat(transport).isInstanceOf(HttpClientSseClientTransport.class);
    }

    @Test
    void validStdioConfigBuildsTransportWithoutSpawningProcess() {
        KeelProperties.Tools.Mcp config = new KeelProperties.Tools.Mcp();
        config.setTransport(KeelProperties.Tools.TRANSPORT_STDIO);
        config.setCommand("echo");
        config.setArgs(List.of("hello"));
        config.setEnv(Map.of("KEEL_TEST", "1"));

        // StdioClientTransport 构造时不启动子进程（initialize / 首次消息时才 spawn）
        McpClientTransport transport = McpClientFactory.buildTransport(config);

        assertThat(transport).isInstanceOf(StdioClientTransport.class);
    }

    // ==================== 多 server 路由（FR-31） ====================

    @Test
    void createServerClientsRejectsEmptyServers() {
        assertThatThrownBy(() -> McpClientFactory.createServerClients(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server");
    }

    @Test
    void createServerClientsRequiresTransportOnEachServer() {
        // 任一 server 缺 transport 在发起网络连接前就失败（纯校验，不触网）
        KeelProperties.Tools.Mcp gitlab = new KeelProperties.Tools.Mcp();

        assertThatThrownBy(() -> McpClientFactory.createServerClients(Map.of("gitlab", gitlab)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gitlab");
    }

    @Test
    void buildToolServerIndexFromExplicitToolsDoesNotTouchClients() {
        KeelProperties.Tools.Mcp gitlab = serverWithTools("gitlab.search_issues", "gitlab.read_mr");
        KeelProperties.Tools.Mcp oa = serverWithTools("oa.list_approvals");

        Map<String, String> index = McpClientFactory.buildToolServerIndex(
                Map.of(), Map.of("gitlab", gitlab, "oa", oa));

        assertThat(index)
                .containsEntry("gitlab.search_issues", "gitlab")
                .containsEntry("gitlab.read_mr", "gitlab")
                .containsEntry("oa.list_approvals", "oa");
    }

    @Test
    void buildToolServerIndexDiscoversToolsViaListTools() {
        KeelProperties.Tools.Mcp gitlab = new KeelProperties.Tools.Mcp();
        KeelProperties.Tools.Mcp oa = new KeelProperties.Tools.Mcp();
        when(gitlabClient.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(new McpSchema.Tool("gitlab.search_issues", "查工单",
                        (McpSchema.JsonSchema) null)),
                null));
        when(oaClient.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(new McpSchema.Tool("oa.list_approvals", "查审批",
                        (McpSchema.JsonSchema) null)),
                null));

        Map<String, String> index = McpClientFactory.buildToolServerIndex(
                Map.of("gitlab", gitlabClient, "oa", oaClient),
                Map.of("gitlab", gitlab, "oa", oa));

        assertThat(index)
                .containsEntry("gitlab.search_issues", "gitlab")
                .containsEntry("oa.list_approvals", "oa");
    }

    @Test
    void buildToolServerIndexRejectsDuplicateToolAcrossServers() {
        KeelProperties.Tools.Mcp gitlab = serverWithTools("shared.tool");
        KeelProperties.Tools.Mcp oa = serverWithTools("shared.tool");

        assertThatThrownBy(() -> McpClientFactory.buildToolServerIndex(
                Map.of(), Map.of("gitlab", gitlab, "oa", oa)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shared.tool");
    }

    private static KeelProperties.Tools.Mcp serverWithTools(String... tools) {
        KeelProperties.Tools.Mcp server = new KeelProperties.Tools.Mcp();
        server.setTools(List.of(tools));
        return server;
    }
}
