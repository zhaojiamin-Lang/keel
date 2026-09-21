package io.keel.graph;

import java.io.Serializable;
import java.util.Objects;

/**
 * 模型在某一步请求调用的原子工具（FR-30）：工具名 + 参数 JSON + 幂等键。
 * 是否为写工具不由模型说了算，而由 ToolPort#isWriteTool 代码判定（FR-13）。
 *
 * <p>{@code idempotencyKey} 可空：保留两参构造器以兼容未升级的 ToolPort 实现与
 * 测试夹具；写工具调用必须显式三参构造，缺键由 PlanNode/ToolNode 代码侧 fail-closed
 * （FR-32 P0）。</p>
 */
public final class ToolCall implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String tool;
    private final String argumentsJson;
    private final String idempotencyKey;

    /** 兼容旧调用方：未带幂等键，透传 null（写路径由节点 fail-closed 兜底）。 */
    public ToolCall(String tool, String argumentsJson) {
        this(tool, argumentsJson, null);
    }

    /** 写工具调用走此构造器，{@code idempotencyKey} 允许为 null，由上游节点判定。 */
    public ToolCall(String tool, String argumentsJson, String idempotencyKey) {
        Objects.requireNonNull(tool, "tool");
        if (tool.isBlank()) {
            throw new IllegalArgumentException("tool must not be blank");
        }
        this.tool = tool;
        // 参数允许为空（无参工具），统一归一化为空串，避免下游 NPE
        this.argumentsJson = argumentsJson == null ? "" : argumentsJson;
        this.idempotencyKey = idempotencyKey;
    }

    public String getTool() {
        return tool;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    /** 幂等键（FR-32/FR-72）：写工具跨进程 dedupe 依据，只读工具或未升级 ToolPort 可能为 null。 */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
