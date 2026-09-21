package io.keel.mcp.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.keel.core.KeelPrincipal;
import io.keel.mcp.auth.CredentialProvider;
import io.modelcontextprotocol.client.McpSyncClient;

/**
 * 多 MCP server 路由网关（FR-31/FR-35）。
 *
 * <ul>
 *   <li>持 serverName → {@link McpSyncClient} 的多个后端，按工具名路由到对应 server；</li>
 *   <li>每个 client 用独立的 {@link SdkMcpToolGateway} 做结果归一化，语义与单 server 一致；</li>
 *   <li>未登记工具 fail-closed：直接返回业务错误，绝不向任意 server 发起调用（FR-35）；</li>
 *   <li>实现 {@link AutoCloseable}，容器关闭时统一释放所有 client 连接 / 结束 stdio 子进程。</li>
 * </ul>
 *
 * <p>FR-31：每个子 gateway 持 server 名，调用前按 server 名解析 per-call 凭证。
 * SDK 0.10.0 无法注入 per-request header，此处只做校验；真正注入需 SDK 升级。</p>
 */
public final class RoutingMcpToolGateway implements McpToolGateway, AutoCloseable {

    private final Map<String, McpSyncClient> clients;
    private final Map<String, SdkMcpToolGateway> gateways;
    private final McpToolRouter router;
    private final CredentialProvider credentialProvider;

    public RoutingMcpToolGateway(Map<String, McpSyncClient> clients, McpToolRouter router) {
        this(clients, router, CredentialProvider.ALLOW_ALL);
    }

    public RoutingMcpToolGateway(Map<String, McpSyncClient> clients, McpToolRouter router,
            CredentialProvider credentialProvider) {
        Objects.requireNonNull(clients, "clients");
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(credentialProvider, "credentialProvider");
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("多 MCP 路由至少需要配置一个 server");
        }
        Map<String, McpSyncClient> clientCopy = new LinkedHashMap<>(clients);
        Map<String, SdkMcpToolGateway> gatewayCopy = new LinkedHashMap<>();
        clientCopy.forEach((name, client) ->
                gatewayCopy.put(name, new SdkMcpToolGateway(client, name, credentialProvider)));
        this.clients = clientCopy;
        this.gateways = gatewayCopy;
        this.router = router;
        this.credentialProvider = credentialProvider;
    }

    @Override
    public GatewayResult callTool(KeelPrincipal principal, String toolName, Map<String, Object> arguments) {
        // 三参只读调用等价于「幂等键为 null」的四参调用：SdkMcpToolGateway 对 null/blank
        // 键会直接走三参路径（FR-32），路由 + fail-closed 逻辑只需在四参重载里单点维护
        return callTool(principal, toolName, arguments, null);
    }

    @Override
    public GatewayResult callTool(
            KeelPrincipal principal, String toolName, Map<String, Object> arguments,
            String idempotencyKey) {
        String server = router.route(toolName).orElse(null);
        if (server == null) {
            // FR-35：未登记工具 fail-closed，拒绝原因不交回模型「自由发挥」
            return GatewayResult.businessError(
                    "工具未登记到任何 MCP server，拒绝调用: " + toolName);
        }
        SdkMcpToolGateway gateway = gateways.get(server);
        if (gateway == null) {
            return GatewayResult.businessError(
                    "工具路由到未配置的 MCP server: " + server);
        }
        // FR-31：per-call 凭证校验由子 gateway（SdkMcpToolGateway）按 serverName 做 fail-closed；
        // FR-32：按工具名路由后由子 gateway 透传幂等键，多 server 与单 server 语义一致
        return gateway.callTool(principal, toolName, arguments, idempotencyKey);
    }

    @Override
    public void close() {
        // 统一释放所有后端连接 / 结束 stdio 子进程；单个失败不影响其余释放
        for (McpSyncClient client : clients.values()) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // 关闭阶段异常不影响容器正常退出
            }
        }
    }
}