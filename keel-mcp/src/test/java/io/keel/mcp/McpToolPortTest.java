package io.keel.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.keel.core.KeelPrincipal;
import io.keel.graph.ToolCall;
import io.keel.graph.ToolObservation;
import io.keel.mcp.gateway.GatewayResult;
import io.keel.mcp.gateway.McpToolGateway;

@ExtendWith(MockitoExtension.class)
class McpToolPortTest {

    @Mock
    private McpToolGateway gateway;

    private McpToolPort port;

    private final KeelPrincipal principal = new KeelPrincipal("tenant", "subject", List.of());

    @BeforeEach
    void setUp() {
        // 退避 1ms：重试用例跑得快；search_orders 登记为只读，其余默认按写处理
        McpToolOptions options = McpToolOptions.builder()
                .readOnlyTools(Set.of("search_orders"))
                .maxRetries(2)
                .retryBackoffMillis(1L)
                .build();
        port = new McpToolPort(gateway, options);
    }

    @Test
    void registeredTool_classifiedAsReadOnly() {
        assertThat(port.isWriteTool("search_orders")).isFalse();
        when(gateway.callTool(eq(principal), eq("search_orders"), any()))
                .thenReturn(GatewayResult.ok("订单 A-1 已签收"));

        ToolObservation observation =
                port.invoke(new ToolCall("search_orders", "{}"), principal);

        assertThat(observation.isSuccess()).isTrue();
        assertThat(observation.getTool()).isEqualTo("search_orders");
        assertThat(observation.getContent()).contains("已签收");
    }

    @Test
    void unregisteredTool_classifiedAsWriteAndBlockedWithoutGatewayCall() {
        assertThat(port.isWriteTool("issue.create")).isTrue();

        ToolObservation observation =
                port.invoke(new ToolCall("issue.create", "{}"), principal);

        assertThat(observation.isSuccess()).isFalse();
        assertThat(observation.getErrorMessage()).contains("fail-closed");
        verifyNoInteractions(gateway);
    }

    @Test
    void blankArguments_passedAsEmptyMap() {
        when(gateway.callTool(eq(principal), eq("search_orders"), any()))
                .thenReturn(GatewayResult.ok("ok"));

        ToolObservation observation =
                port.invoke(new ToolCall("search_orders", "   "), principal);

        assertThat(observation.isSuccess()).isTrue();
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(gateway).callTool(eq(principal), eq("search_orders"), captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void invalidArgumentsJson_returnsFailedObservationWithoutGatewayCall() {
        ToolObservation observation =
                port.invoke(new ToolCall("search_orders", "{not-json"), principal);

        assertThat(observation.isSuccess()).isFalse();
        assertThat(observation.getErrorMessage()).contains("JSON");
        verifyNoInteractions(gateway);
    }

    @Test
    void transportFailsTwiceThenSucceedsWithinBudget() {
        when(gateway.callTool(eq(principal), eq("search_orders"), any()))
                .thenThrow(new RuntimeException("连接断开"))
                .thenThrow(new RuntimeException("连接断开"))
                .thenReturn(GatewayResult.ok("已恢复"));

        ToolObservation observation =
                port.invoke(new ToolCall("search_orders", "{}"), principal);

        assertThat(observation.isSuccess()).isTrue();
        assertThat(observation.getContent()).isEqualTo("已恢复");
        verify(gateway, times(3)).callTool(eq(principal), eq("search_orders"), any());
    }

    @Test
    void transportFailure_retriedUntilBudgetExhausted() {
        when(gateway.callTool(eq(principal), eq("search_orders"), any()))
                .thenThrow(new RuntimeException("连接断开"));

        ToolObservation observation =
                port.invoke(new ToolCall("search_orders", "{}"), principal);

        assertThat(observation.isSuccess()).isFalse();
        assertThat(observation.getErrorMessage()).contains("重试预算耗尽");
        // 1 次初始调用 + 2 次重试
        verify(gateway, times(3)).callTool(eq(principal), eq("search_orders"), any());
    }

    @Test
    void businessError_isNotRetried() {
        when(gateway.callTool(eq(principal), eq("search_orders"), any()))
                .thenReturn(GatewayResult.businessError("权限不足"));

        ToolObservation observation =
                port.invoke(new ToolCall("search_orders", "{}"), principal);

        assertThat(observation.isSuccess()).isFalse();
        assertThat(observation.getErrorMessage()).contains("权限不足");
        // 业务错误是确定性失败：只调用一次，绝不重试
        verify(gateway, times(1)).callTool(any(), any(), any());
    }

    @Test
    void readOnlySuccess_textIsRedactedBeforeObservation() {
        when(gateway.callTool(eq(principal), eq("search_orders"), any()))
                .thenReturn(GatewayResult.ok("身份证 110101199003078888 已核验"));

        ToolObservation observation =
                port.invoke(new ToolCall("search_orders", "{}"), principal);

        assertThat(observation.isSuccess()).isTrue();
        assertThat(observation.getContent())
                .doesNotContain("110101199003078888")
                .contains("[REDACTED]")
                .contains("已核验");
    }
}
