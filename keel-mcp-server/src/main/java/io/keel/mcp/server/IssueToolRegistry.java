package io.keel.mcp.server;


import java.util.List;
import java.util.Map;

/**
 * FR-34：MCP Server 工具注册表——声明 server 暴露的工具及其权限级别。
 *
 * <p>参考实现展示如何声明只读 vs 受限写工具，以及如何做工具调用分发。</p>
 */
public class IssueToolRegistry {

    public static final String TOOL_SEARCH = "issue.search";
    public static final String TOOL_CREATE = "issue.create";

    private final InMemoryIssueServer server;

    public IssueToolRegistry(InMemoryIssueServer server) {
        this.server = server;
    }

    /** 返回所有已注册工具的元数据 */
    public List<ToolMetadata> listTools() {
        return List.of(
                new ToolMetadata(TOOL_SEARCH, "按关键词搜索 issue", false),
                new ToolMetadata(TOOL_CREATE, "创建新 issue（需幂等键）", true));
    }

    /** 调用工具 */
    public ToolResult callTool(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case TOOL_SEARCH -> {
                String keyword = (String) arguments.getOrDefault("keyword", "");
                List<InMemoryIssueServer.Issue> results = server.searchIssues(keyword);
                yield new ToolResult(true, "Found " + results.size() + " issues", results);
            }
            case TOOL_CREATE -> {
                String idempotencyKey = (String) arguments.get("idempotencyKey");
                String title = (String) arguments.get("title");
                String description = (String) arguments.getOrDefault("description", "");
                try {
                    InMemoryIssueServer.Issue issue = server.createIssue(idempotencyKey, title, description);
                    yield new ToolResult(true, "Created issue: " + issue.id(), issue);
                } catch (IllegalArgumentException e) {
                    yield new ToolResult(false, e.getMessage(), null);
                }
            }
            default -> new ToolResult(false, "Unknown tool: " + toolName, null);
        };
    }

    /** 工具元数据 */
    public record ToolMetadata(String name, String description, boolean writable) {
    }

    /** 工具调用结果 */
    public record ToolResult(boolean success, String message, Object data) {
    }
}