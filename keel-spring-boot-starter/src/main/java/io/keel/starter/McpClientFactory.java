package io.keel.starter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.util.StringUtils;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.keel.mcp.auth.PerCallCredentialContext;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP 同步客户端工厂（FR-31）：根据 keel.tools.mcp.* 构建 transport 并完成 initialize 握手。
 * <ul>
 *   <li>sse：HttpClientSseClientTransport（base-url + endpoint + Bearer token）；</li>
 *   <li>stdio：StdioClientTransport + ServerParameters（command/args/env）；</li>
 *   <li>配置非法时快速失败（IllegalArgumentException），不启动半残 client。</li>
 * </ul>
 * 注意：本类只负责「建连」，业务自定义 McpSyncClient bean 时不会被调用。
 */
public final class McpClientFactory {

    private McpClientFactory() {
    }

    /**
     * 按配置构建 transport。纯构造：不触网、不拉起子进程（SDK 在 connect/initialize 时才连）。
     */
    static McpClientTransport buildTransport(KeelProperties.Tools.Mcp config) {
        String transport = config.getTransport();
        if (!StringUtils.hasText(transport)) {
            throw new IllegalArgumentException(
                    "keel.tools.mcp.transport 未配置（支持 sse / stdio）");
        }
        switch (transport) {
            case KeelProperties.Tools.TRANSPORT_SSE:
                return buildSseTransport(config);
            case KeelProperties.Tools.TRANSPORT_STDIO:
                return buildStdioTransport(config);
            default:
                throw new IllegalArgumentException(
                        "未知的 keel.tools.mcp.transport: " + transport + "（支持 sse / stdio）");
        }
    }

    private static McpClientTransport buildSseTransport(KeelProperties.Tools.Mcp config) {
        if (!StringUtils.hasText(config.getBaseUrl())) {
            throw new IllegalArgumentException(
                    "sse transport 必须配置 keel.tools.mcp.base-url");
        }
        HttpClientSseClientTransport.Builder builder =
                HttpClientSseClientTransport.builder(config.getBaseUrl())
                        .sseEndpoint(config.getSseEndpoint());
        // FR-31：per-request header 注入。
        // customizeRequest 在每次 HTTP 请求时回调：
        //   1. 先读 ThreadLocal 中的 per-call 凭证（由 SdkMcpToolGateway 设置）；
        //   2. 空 则用配置的静态 token（向后兼容）。
        final String staticToken = config.getToken();
        builder.customizeRequest(requestBuilder -> {
            // 优先 per-call 凭证
            String perCallCredential = PerCallCredentialContext.get();
            if (perCallCredential != null && !perCallCredential.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + perCallCredential);
            } else if (StringUtils.hasText(staticToken)) {
                // 回退静态 token
                requestBuilder.header("Authorization", "Bearer " + staticToken);
            }
        });
        return builder.build();
    }

    private static McpClientTransport buildStdioTransport(KeelProperties.Tools.Mcp config) {
        if (!StringUtils.hasText(config.getCommand())) {
            throw new IllegalArgumentException(
                    "stdio transport 必须配置 keel.tools.mcp.command");
        }
        ServerParameters.Builder paramsBuilder = ServerParameters.builder(config.getCommand());
        if (config.getArgs() != null && !config.getArgs().isEmpty()) {
            paramsBuilder.args(config.getArgs());
        }
        for (Map.Entry<String, String> entry : config.getEnv().entrySet()) {
            paramsBuilder.addEnvVar(entry.getKey(), entry.getValue());
        }
        // 构造不启动进程；SDK 在 initialize/首次消息时才 spawn
        return new StdioClientTransport(paramsBuilder.build());
    }

    /** 构建同步 client 并立即执行 MCP initialize 握手；失败直接抛出，fail-fast。 */
    public static McpSyncClient createInitializedClient(KeelProperties.Tools.Mcp config) {
        McpSyncClient client = McpClient.sync(buildTransport(config))
                .requestTimeout(config.getRequestTimeout())
                .initializationTimeout(config.getInitializationTimeout())
                .build();
        client.initialize();
        return client;
    }

    /**
     * 多 server 路由（FR-31）：按 serverName 逐一建连并完成 initialize。
     * 任一 server 缺少 transport 或握手失败都会 fail-fast，不启动半残的聚合网关。
     */
    public static Map<String, McpSyncClient> createServerClients(
            Map<String, KeelProperties.Tools.Mcp> servers) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("多 MCP 路由未配置任何 server");
        }
        Map<String, McpSyncClient> clients = new LinkedHashMap<>();
        servers.forEach((name, config) -> {
            if (config == null || !StringUtils.hasText(config.getTransport())) {
                throw new IllegalArgumentException(
                        "MCP server '" + name + "' 缺少 transport（支持 sse / stdio）");
            }
            clients.put(name, createInitializedClient(config));
        });
        return clients;
    }

    /**
     * 构建 toolName → serverName 索引。显式配置的 {@code tools} 优先；为空时用
     * {@code listTools()} 自动发现该 server 暴露的工具。同一工具登记到多个 server
     * 属于配置错误，fail-fast 拒绝（FR-35）。
     */
    public static Map<String, String> buildToolServerIndex(
            Map<String, McpSyncClient> clients,
            Map<String, KeelProperties.Tools.Mcp> servers) {
        Map<String, String> index = new LinkedHashMap<>();
        servers.forEach((serverName, config) -> {
            List<String> tools = config.getTools();
            List<String> resolved;
            if (tools != null && !tools.isEmpty()) {
                resolved = tools;
            } else {
                McpSyncClient client = clients.get(serverName);
                if (client == null) {
                    throw new IllegalArgumentException(
                            "MCP server '" + serverName + "' 无对应已建连 client");
                }
                resolved = client.listTools().tools().stream()
                        .map(McpSchema.Tool::name)
                        .toList();
            }
            for (String tool : resolved) {
                if (tool == null || tool.isBlank()) {
                    continue;
                }
                String previous = index.putIfAbsent(tool, serverName);
                if (previous != null && !previous.equals(serverName)) {
                    throw new IllegalArgumentException(
                            "工具 '" + tool + "' 同时登记在多个 MCP server: "
                                    + previous + " / " + serverName);
                }
            }
        });
        return index;
    }
}
