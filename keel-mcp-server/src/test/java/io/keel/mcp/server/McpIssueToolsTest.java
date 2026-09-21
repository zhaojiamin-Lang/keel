package io.keel.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * {@link McpIssueTools} 单元测试（FR-34）：工具规格 / 分发 / 幂等 / 错误转 isError。
 * 直接调用 SyncToolSpecification 的 callHandler，不依赖传输层。
 */
class McpIssueToolsTest {

    private final IssueToolRegistry registry =
            new IssueToolRegistry(new InMemoryIssueServer());
    private final List<SyncToolSpecification> specs = McpIssueTools.create(registry);

    @Test
    void exposesTwoToolsWithDeclaredNames() {
        List<String> names = specs.stream().map(spec -> spec.tool().name()).toList();
        assertThat(names).containsExactly(
                IssueToolRegistry.TOOL_SEARCH, IssueToolRegistry.TOOL_CREATE);
        // 受限写工具必须在 schema 中声明 idempotencyKey 必填
        SyncToolSpecification create = specs.get(1);
        assertThat(create.tool().inputSchema().toString()).contains("idempotencyKey");
    }

    @Test
    void searchReturnsPlainTextResult() {
        registry.callTool(IssueToolRegistry.TOOL_CREATE, Map.of(
                "idempotencyKey", "k1", "title", "登录失败", "description", "超时"));

        CallToolResult result = call(IssueToolRegistry.TOOL_SEARCH, Map.of("keyword", "登录"));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("Found 1 issues");
    }

    @Test
    void createRequiresIdempotencyKey() {
        CallToolResult result = call(IssueToolRegistry.TOOL_CREATE, Map.of("title", "无幂等键"));

        // 缺幂等键：isError=true（写边界用代码兜底，不依赖客户端自觉）
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("idempotencyKey");
    }

    @Test
    void createIsIdempotentPerKey() {
        CallToolResult first = call(IssueToolRegistry.TOOL_CREATE, Map.of(
                "idempotencyKey", "same-key", "title", "工单 A"));
        CallToolResult second = call(IssueToolRegistry.TOOL_CREATE, Map.of(
                "idempotencyKey", "same-key", "title", "工单 A（重放）"));

        assertThat(first.isError()).isFalse();
        assertThat(second.isError()).isFalse();
        // 重放不产生第二次副作用：两次返回同一 issue id
        assertThat(text(second)).isEqualTo(text(first));
        assertThat(text(second)).contains("ISSUE-");
    }

    @Test
    void unknownToolIsError() {
        // 未知工具在 spec 层不存在（MCP SDK 协议层会先拦截），
        // 这里直接验证注册表的兜底分支
        IssueToolRegistry.ToolResult result = registry.callTool("issue.delete", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Unknown tool");
    }

    private CallToolResult call(String toolName, Map<String, Object> args) {
        SyncToolSpecification spec = specs.stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow();
        return spec.call().apply(null, args);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}
