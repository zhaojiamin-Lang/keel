package io.keel.mcp.server;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * FR-34：把 {@link IssueToolRegistry} 的业务工具适配为 MCP 官方 SDK 的
 * {@link McpServerFeatures.SyncToolSpecification} 列表。
 *
 * <p>权限边界在这里用代码表达（与 keel-guard 同一原则，不写在 Prompt 里）：</p>
 * <ul>
 *   <li>{@code issue.search}：只读，无必填参数；</li>
 *   <li>{@code issue.create}：受限写，inputSchema 声明 {@code idempotencyKey} 必填，
 *       业务层（InMemoryIssueServer）再兜底校验，缺键时返回 isError。</li>
 * </ul>
 */
public final class McpIssueTools {

    private McpIssueTools() {
    }

    public static List<McpServerFeatures.SyncToolSpecification> create(IssueToolRegistry registry) {
        // 只读工具：keyword 可选
        McpSchema.JsonSchema searchInput = new McpSchema.JsonSchema(
                "object",
                Map.of("keyword", Map.of("type", "string", "description", "搜索关键词（可选）")),
                List.of(),
                null,
                null,
                null);
        Tool search = new Tool(
                IssueToolRegistry.TOOL_SEARCH, "按关键词搜索 issue（只读）", searchInput);

        // 受限写工具：idempotencyKey 必填（幂等防重）
        McpSchema.JsonSchema createInput = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "idempotencyKey", Map.of("type", "string", "description", "幂等键，防重复创建"),
                        "title", Map.of("type", "string"),
                        "description", Map.of("type", "string")),
                List.of("idempotencyKey", "title"),
                null,
                null,
                null);
        Tool create = new Tool(
                IssueToolRegistry.TOOL_CREATE, "创建新 issue（受限写，需幂等键）", createInput);

        return List.of(
                new McpServerFeatures.SyncToolSpecification(search, (exchange, args) ->
                        toCallResult(registry.callTool(IssueToolRegistry.TOOL_SEARCH, args))),
                new McpServerFeatures.SyncToolSpecification(create, (exchange, args) ->
                        toCallResult(registry.callTool(IssueToolRegistry.TOOL_CREATE, args))));
    }

    /** ToolResult → CallToolResult：失败必须 isError=true，客户端可感知而不是拿到正常文本。 */
    private static CallToolResult toCallResult(IssueToolRegistry.ToolResult result) {
        return new CallToolResult(List.of(new TextContent(result.message())), !result.success());
    }
}
