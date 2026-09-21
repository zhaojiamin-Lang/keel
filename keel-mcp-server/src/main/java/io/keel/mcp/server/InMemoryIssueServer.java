package io.keel.mcp.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * FR-34：MCP Server 参考实现——基于内存的 Issue 存储。
 *
 * <p>提供两个工具：</p>
 * <ul>
 *   <li>{@code issue.search}（只读）：按关键词搜索 issue；</li>
 *   <li>{@code issue.create}（受限写）：创建新 issue，需幂等键防重。</li>
 * </ul>
 *
 * <p>本类是参考实现，不依赖 MCP SDK 的 server 侧 API（0.10.0 尚未稳定），
 * 而是提供业务逻辑层，可被任何 MCP Server 框架包装暴露。</p>
 */
public class InMemoryIssueServer {

    private final Map<String, Issue> issues = new LinkedHashMap<>();

    /** 只读：按关键词搜索 issue */
    public List<Issue> searchIssues(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.copyOf(issues.values());
        }
        String lower = keyword.toLowerCase();
        return issues.values().stream()
                .filter(i -> i.title().toLowerCase().contains(lower)
                        || i.description().toLowerCase().contains(lower))
                .collect(Collectors.toList());
    }

    /** 只读：按 ID 查询 issue */
    public Optional<Issue> getIssue(String id) {
        return Optional.ofNullable(issues.get(id));
    }

    /** 受限写：创建 issue，需幂等键防重 */
    public Issue createIssue(String idempotencyKey, String title, String description) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required for write operations");
        }
        // 幂等：相同 key 返回已创建的 issue
        Issue existing = issues.values().stream()
                .filter(i -> idempotencyKey.equals(i.idempotencyKey()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        String id = "ISSUE-" + (issues.size() + 1);
        Issue issue = new Issue(id, title, description, idempotencyKey);
        issues.put(id, issue);
        return issue;
    }

    /** issue 数据模型 */
    public record Issue(String id, String title, String description, String idempotencyKey) {
    }
}