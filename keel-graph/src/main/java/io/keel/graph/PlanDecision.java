package io.keel.graph;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Planner（模型）对当前状态的决策。只允许四种动作：
 * <ul>
 *     <li>{@link Kind#ANSWER}：给出最终草稿答案，进入 Critic；</li>
 *     <li>{@link Kind#RETRIEVE}：需要知识检索，{@code text} 为检索 query；</li>
 *     <li>{@link Kind#CALL_TOOL}：请求调用工具（是否真的是只读由代码二次判定）；</li>
 *     <li>{@link Kind#PROPOSE_WRITE}：提议写操作，只能生成待确认动作，不得执行（FR-13）。</li>
 * </ul>
 */
public final class PlanDecision implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public enum Kind {
        ANSWER,
        RETRIEVE,
        CALL_TOOL,
        PROPOSE_WRITE
    }

    private final Kind kind;
    private final String text;
    private final ToolCall toolCall;

    private PlanDecision(Kind kind, String text, ToolCall toolCall) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.text = text == null ? "" : text;
        this.toolCall = toolCall;
    }

    public static PlanDecision answer(String answer) {
        Objects.requireNonNull(answer, "answer");
        return new PlanDecision(Kind.ANSWER, answer, null);
    }

    public static PlanDecision retrieve(String query) {
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("retrieve query must not be blank");
        }
        return new PlanDecision(Kind.RETRIEVE, query, null);
    }

    public static PlanDecision callTool(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall");
        return new PlanDecision(Kind.CALL_TOOL, "", toolCall);
    }

    public static PlanDecision proposeWrite(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall");
        return new PlanDecision(Kind.PROPOSE_WRITE, "", toolCall);
    }

    public Kind getKind() {
        return kind;
    }

    /** ANSWER 时为答案文本；RETRIEVE 时为检索 query；其余为 ""。 */
    public String getText() {
        return text;
    }

    public ToolCall getToolCall() {
        return toolCall;
    }
}
