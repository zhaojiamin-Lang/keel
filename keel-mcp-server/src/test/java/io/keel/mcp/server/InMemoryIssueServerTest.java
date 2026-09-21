package io.keel.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryIssueServerTest {

    private InMemoryIssueServer server;
    private IssueToolRegistry registry;

    @BeforeEach
    void setUp() {
        server = new InMemoryIssueServer();
        registry = new IssueToolRegistry(server);
    }

    @Test
    void searchEmptyReturnsAll() {
        server.createIssue("key-1", "Bug in login", "User cannot login");
        server.createIssue("key-2", "Feature request", "Add dark mode");

        List<InMemoryIssueServer.Issue> results = server.searchIssues("");

        assertThat(results).hasSize(2);
    }

    @Test
    void searchByKeywordReturnsMatching() {
        server.createIssue("key-1", "Bug in login", "User cannot login");
        server.createIssue("key-2", "Feature request", "Add dark mode");

        List<InMemoryIssueServer.Issue> results = server.searchIssues("login");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).contains("login");
    }

    @Test
    void createIssueWithIdempotencyKeyIsIdempotent() {
        InMemoryIssueServer.Issue first = server.createIssue("key-1", "Bug", "Description");
        InMemoryIssueServer.Issue second = server.createIssue("key-1", "Bug", "Description");

        assertThat(first).isEqualTo(second);
        assertThat(server.searchIssues("")).hasSize(1);
    }

    @Test
    void createIssueWithoutIdempotencyKeyFails() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> server.createIssue(null, "Bug", "Description"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolRegistryListsReadOnlyAndWriteTools() {
        List<IssueToolRegistry.ToolMetadata> tools = registry.listTools();

        assertThat(tools).hasSize(2);
        assertThat(tools).extracting(IssueToolRegistry.ToolMetadata::writable)
                .containsExactly(false, true);
    }

    @Test
    void toolRegistryCallSearchReturnsResults() {
        server.createIssue("key-1", "Bug", "login issue");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", "bug");

        IssueToolRegistry.ToolResult result = registry.callTool("issue.search", args);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isNotNull();
    }

    @Test
    void toolRegistryCallCreateWithoutIdempotencyKeyFails() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("title", "Bug");
        // 没有 idempotencyKey

        IssueToolRegistry.ToolResult result = registry.callTool("issue.create", args);

        assertThat(result.success()).isFalse();
    }
}