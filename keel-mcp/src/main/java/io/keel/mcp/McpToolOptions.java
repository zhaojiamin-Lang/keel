package io.keel.mcp;

import java.util.Set;

/**
 * MCP 只读工具端口的运行参数：
 * 只读注册表 + 重试预算 + 脱敏规则。
 */
public final class McpToolOptions {

    private final Set<String> readOnlyTools;

    private final int maxRetries;

    private final long retryBackoffMillis;

    private final ContentRedactor contentRedactor;

    private McpToolOptions(Builder builder) {
        this.readOnlyTools = builder.readOnlyTools;
        this.maxRetries = builder.maxRetries;
        this.retryBackoffMillis = builder.retryBackoffMillis;
        this.contentRedactor = builder.contentRedactor;
    }

    public Set<String> readOnlyTools() {
        return readOnlyTools;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public long retryBackoffMillis() {
        return retryBackoffMillis;
    }

    public ContentRedactor contentRedactor() {
        return contentRedactor;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Set<String> readOnlyTools = Set.of();

        private int maxRetries = 2;

        private long retryBackoffMillis = 100L;

        private ContentRedactor contentRedactor;

        public Builder readOnlyTools(Set<String> readOnlyTools) {
            this.readOnlyTools = Set.copyOf(readOnlyTools == null ? Set.of() : readOnlyTools);
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryBackoffMillis(long retryBackoffMillis) {
            this.retryBackoffMillis = retryBackoffMillis;
            return this;
        }

        public Builder contentRedactor(ContentRedactor contentRedactor) {
            this.contentRedactor = contentRedactor;
            return this;
        }

        public McpToolOptions build() {
            return new McpToolOptions(this);
        }
    }
}
