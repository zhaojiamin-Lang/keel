package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

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
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * graph + MCP 默认开启时的端到端装配测试：业务自定义 McpSyncClient（mock，不触网）
 * 被自动拾取，验证 ①CALL_TOOL→观察→ANSWER 成功链路 ②skill 越权工具直接拒绝（FR-32）。
 */
@SpringBootTest(properties = {
        "keel.agent.mode=graph",
        "keel.tools.allowed[0]=search_orders",
        "keel.tools.allowed[1]=issue.create",
        "keel.tools.allowed[2]=gitlab.search_issues",
        "keel.tools.allowed[3]=oa.list_approvals"
})
class GraphMcpAutoConfigurationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private GraphRagTestFixtures.QueuedChatModel chatModel;

    @Autowired
    private McpSyncClient mcpSyncClient;

    @BeforeEach
    void resetContext() {
        reset(mcpSyncClient);
        when(mcpSyncClient.callTool(any()))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("订单 A-1 状态：已发货")),
                        false));
        chatModel.reset(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"search_orders\","
                        + "\"arguments\":{\"orderId\":\"A-1\"}}",
                "{\"action\":\"ANSWER\",\"answer\":\"根据工具返回，订单 A-1 已发货\"}",
                "{\"verdict\":\"APPROVED\"}");
    }

    @Test
    void callReadOnlyToolThenAnswerSucceeds() {
        AgentResult result = keelAgent.run(request("mcp-query", "查一下 A-1 订单"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("订单 A-1 已发货");

        ArgumentCaptor<McpSchema.CallToolRequest> captor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(mcpSyncClient).callTool(captor.capture());
        McpSchema.CallToolRequest request = captor.getValue();
        assertThat(request.name()).isEqualTo("search_orders");
        assertThat(request.arguments()).containsEntry("orderId", "A-1");
    }

    @Test
    void toolNotAllowedBySkillIsRejectedWithoutInvocation() {
        chatModel.reset(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"secret_lookup\","
                        + "\"arguments\":{}}");

        AgentResult result = keelAgent.run(request("mcp-query", "查点敏感数据"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.REJECTED);
        assertThat(result.getErrorCode()).isEqualTo("TOOL_NOT_PERMITTED");
        // 拒绝原因是代码裁决，绝不能真正发起 MCP 调用
        verify(mcpSyncClient, never()).callTool(any());
    }

    private AgentRequest request(String skill, String input) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .skill(skill)
                .input(input)
                .build();
    }

    @SpringBootApplication
    static class McpTestApplication {

        @Bean
        ChatModel chatModel() {
            return new GraphRagTestFixtures.QueuedChatModel();
        }

        /** 业务自定义 client：自动配置不再创建默认 client，网关 / ToolPort 仍正常装配。 */
        @Bean
        McpSyncClient mcpSyncClient() {
            return Mockito.mock(McpSyncClient.class);
        }
    }
}
