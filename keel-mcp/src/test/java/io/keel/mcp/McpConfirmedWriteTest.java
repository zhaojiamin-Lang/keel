package io.keel.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.keel.core.KeelPrincipal;
import io.keel.graph.ToolCall;
import io.keel.graph.ToolObservation;
import io.keel.mcp.gateway.GatewayResult;
import io.keel.mcp.gateway.McpToolGateway;

/**
 * 确认后的写工具执行（FR-13/FR-31/FR-32）：
 * <ul>
 *   <li>未确认路径 {@code invoke} 对写工具仍然 fail-closed；</li>
 *   <li>确认路径 {@code invokeConfirmedWrite} 放开写限制，幂等键随每次调用透传；</li>
 *   <li>传输类重试携带同一幂等键（server 端按键去重），业务错误不重试；</li>
 *   <li>写结果同样先脱敏再进图状态。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class McpConfirmedWriteTest {

    @Mock
    private McpToolGateway gateway;

    private McpToolPort port;

    private final KeelPrincipal principal = new KeelPrincipal("tenant", "subject", List.of());

    @BeforeEach
    void setUp() {
        // issue.create 未登记为只读 → 按写工具处理；退避 1ms 加速重试用例
        McpToolOptions options = McpToolOptions.builder()
                .readOnlyTools(Set.of("search_orders"))
                .maxRetries(2)
                .retryBackoffMillis(1L)
                .build();
        port = new McpToolPort(gateway, options);
    }

    @Test
    void unconfirmedInvokeStillBlocksWriteTools() {
        ToolObservation observation =
                port.invoke(new ToolCall("issue.create", "{}", "idem-1"), principal);

        assertThat(observation.isSuccess()).isFalse();
        assertThat(observation.getErrorMessage()).contains("fail-closed");
        verifyNoInteractions(gateway);
    }

    @Test
    void confirmedWriteReachesGatewayWithIdempotencyKey() {
        when(gateway.callTool(eq(principal), eq("issue.create"), any(), eq("idem-1")))
                .thenReturn(GatewayResult.ok("工单已创建: T-1"));

        ToolObservation observation = port.invokeConfirmedWrite(
                new ToolCall("issue.create", "{\"title\":\"子任务\"}", "idem-1"), principal);

        assertThat(observation.isSuccess()).isTrue();
        assertThat(observation.getContent()).contains("T-1");
        // 确认路径必须走四参重载（带幂等键），且绝不退化到无键调用
        verify(gateway).callTool(eq(principal), eq("issue.create"), any(), eq("idem-1"));
        verify(gateway, never()).callTool(eq(principal), eq("issue.create"), any());
    }

    @Test
    void confirmedWriteTransportRetryCarriesSameIdempotencyKey() {
        // FR-32：传输类故障重试时每次都带同一幂等键，server 端按键去重不产生第二笔副作用
        when(gateway.callTool(eq(principal), eq("issue.create"), any(), eq("idem-2")))
                .thenThrow(new RuntimeException("连接断开"))
                .thenThrow(new RuntimeException("连接断开"))
                .thenReturn(GatewayResult.ok("已创建"));

        ToolObservation observation = port.invokeConfirmedWrite(
                new ToolCall("issue.create", "{}", "idem-2"), principal);

        assertThat(observation.isSuccess()).isTrue();
        verify(gateway, times(3)).callTool(eq(principal), eq("issue.create"), any(), eq("idem-2"));
    }

    @Test
    void confirmedWriteBusinessErrorIsNotRetried() {
        when(gateway.callTool(eq(principal), eq("issue.create"), any(), eq("idem-3")))
                .thenReturn(GatewayResult.businessError("状态机不允许该变更"));

        ToolObservation observation = port.invokeConfirmedWrite(
                new ToolCall("issue.create", "{}", "idem-3"), principal);

        assertThat(observation.isSuccess()).isFalse();
        assertThat(observation.getErrorMessage()).contains("状态机");
        // 业务错误是确定性失败：只调用一次，绝不重试
        verify(gateway, times(1)).callTool(any(), anyString(), any(), anyString());
    }

    @Test
    void confirmedWriteResultIsRedactedBeforeObservation() {
        when(gateway.callTool(eq(principal), eq("issue.create"), any(), eq("idem-4")))
                .thenReturn(GatewayResult.ok("创建成功，联系手机 13812345678"));

        ToolObservation observation = port.invokeConfirmedWrite(
                new ToolCall("issue.create", "{}", "idem-4"), principal);

        assertThat(observation.isSuccess()).isTrue();
        assertThat(observation.getContent())
                .doesNotContain("13812345678")
                .contains("[REDACTED]");
    }
}
