package io.keel.mcp.gateway;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import io.keel.core.KeelPrincipal;
import io.keel.mcp.auth.CredentialProvider;
import io.keel.mcp.auth.PerCallCredentialContext;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * 基于官方 SDK {@link McpSyncClient} 的网关实现：只做调用与结果归一化，
 * 不做写身份判定（身份判定在 McpToolPort，以本地注册表为准）。
 *
 * <p>FR-31：调用前按 principal 解析 per-call 凭证。SDK 0.10.0 的
 * CallToolRequest 不支持 per-request header，此处只做凭证校验（fail-closed）；
 * 真正的 per-call header 注入需 SDK 升级（>= 0.11）。</p>
 */
public class SdkMcpToolGateway implements McpToolGateway {

    private static final String DEFAULT_SERVER = "default";

    /**
     * FR-32：幂等键透传给 MCP server 的保留参数名。
     * server 端据此做跨进程去重（重试不产生第二笔副作用）；
     * 不识别该参数的 server 会安全忽略，业务侧幂等仍是第一道防线。
     */
    public static final String IDEMPOTENCY_KEY_ARG = "_keel_idempotency_key";

    private final McpSyncClient mcpClient;
    private final CredentialProvider credentialProvider;
    private final String serverName;

    public SdkMcpToolGateway(McpSyncClient mcpClient) {
        this(mcpClient, DEFAULT_SERVER, CredentialProvider.ALLOW_ALL);
    }

    public SdkMcpToolGateway(McpSyncClient mcpClient, CredentialProvider credentialProvider) {
        this(mcpClient, DEFAULT_SERVER, credentialProvider);
    }

    public SdkMcpToolGateway(McpSyncClient mcpClient, String serverName, CredentialProvider credentialProvider) {
        this.mcpClient = Objects.requireNonNull(mcpClient, "mcpClient");
        this.serverName = Objects.requireNonNull(serverName, "serverName");
        this.credentialProvider = Objects.requireNonNull(credentialProvider, "credentialProvider");
    }

    @Override
    public GatewayResult callTool(
            KeelPrincipal principal, String toolName, Map<String, Object> arguments,
            String idempotencyKey) {
        // FR-32：幂等键并入参数副本透传；无键（只读路径）等价于三参调用
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return callTool(principal, toolName, arguments);
        }
        Map<String, Object> argsWithKey = new java.util.LinkedHashMap<>(
                arguments == null ? Map.of() : arguments);
        argsWithKey.put(IDEMPOTENCY_KEY_ARG, idempotencyKey);
        return callTool(principal, toolName, argsWithKey);
    }

    @Override
    public GatewayResult callTool(KeelPrincipal principal, String toolName, Map<String, Object> arguments) {
        // FR-31：per-call 凭证校验 + fail-closed + ThreadLocal 注入。
        // 若 server 有凭证要求（isCredentialRequired=true）但 principal 无法解析到凭证
        // （resolveCredential 返回 empty），则 fail-closed 拒绝调用。
        // 凭证校验通过后，放入 ThreadLocal，SSE transport 的 customizeRequest 回调
        // 从中读取并注入 Authorization header——真正的 per-request header 注入。
        if (principal != null && credentialProvider.isCredentialRequired(serverName)) {
            var credential = credentialProvider.resolveCredential(principal, serverName);
            if (credential.isEmpty()) {
                return GatewayResult.businessError(
                        "凭证校验失败：principal 无权访问 server '" + serverName + "'，调用被拒绝");
            }
            // 注入 ThreadLocal，SSE transport 会读取并注入 Authorization header
            PerCallCredentialContext.set(credential.get());
        }
        try {
            McpSchema.CallToolResult result = mcpClient.callTool(
                    new McpSchema.CallToolRequest(toolName, arguments));
            String text = extractText(result);
            // isError=true 是 MCP 协议表达的「业务失败」（工具执行了，但返回错误），
            // 与传输异常不同：重试不会改变结果，直接交回图里走 OBSERVE/CRITIC。
            if (Boolean.TRUE.equals(result.isError())) {
                return GatewayResult.businessError(text);
            }
            return GatewayResult.ok(text);
        } catch (McpError e) {
            // JSON-RPC 错误响应（方法不存在 / 参数非法 / 内部错误）：确定性失败，不重试。
            return GatewayResult.businessError("MCP error: " + e.getMessage());
        } finally {
            // FR-31：清除 ThreadLocal，防止泄漏到后续请求
            PerCallCredentialContext.clear();
        }
        // 其余 RuntimeException（连接断开 / 超时）保持抛出，由 McpToolPort 按预算重试。
    }

    /** 只取 TextContent；图片 / 音频等非文本块在只读文本场景下忽略，空结果给占位说明。 */
    private String extractText(McpSchema.CallToolResult result) {
        List<McpSchema.Content> contents = result.content();
        String text = contents.stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
        return text.isEmpty() ? "（工具未返回文本内容）" : text;
    }
}