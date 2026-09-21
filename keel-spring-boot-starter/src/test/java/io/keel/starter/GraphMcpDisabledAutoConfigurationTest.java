package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.KeelPrincipal;
import io.keel.graph.spi.ToolPort;

/**
 * keel.tools.mcp.enabled=false 时：整个 MCP 自动配置跳过，容器内无 ToolPort；
 * 模型请求调用工具必须 fail-closed（NO_TOOL_PORT），而不是静默忽略。
 */
@SpringBootTest(properties = {
        "keel.agent.mode=graph",
        "keel.tools.mcp.enabled=false"
})
class GraphMcpDisabledAutoConfigurationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private GraphRagTestFixtures.QueuedChatModel chatModel;

    @Autowired
    private ObjectProvider<ToolPort> toolPorts;

    @BeforeEach
    void resetChat() {
        chatModel.reset(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"search_orders\","
                        + "\"arguments\":{\"orderId\":\"A-1\"}}");
    }

    @Test
    void toolRequestFailsClosedWhenMcpDisabled() {
        assertThat(toolPorts.getIfAvailable()).isNull();

        AgentResult result = keelAgent.run(request("mcp-query", "查一下 A-1 订单"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("NO_TOOL_PORT");
    }

    private AgentRequest request(String skill, String input) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .skill(skill)
                .input(input)
                .build();
    }

    @SpringBootApplication
    static class McpDisabledTestApplication {

        @Bean
        ChatModel chatModel() {
            return new GraphRagTestFixtures.QueuedChatModel();
        }
    }
}
