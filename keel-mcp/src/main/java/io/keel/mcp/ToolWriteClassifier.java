package io.keel.mcp;

import java.util.Set;

/**
 * 写工具判定器（FR-32）：Guard 以代码落地，不靠模型自觉，也不信服务端 hint。
 * 唯一权威是本地只读工具名注册表（keel.tools.allowed）；
 * 未登记的工具一律按写处理（fail-closed），默认拒绝，必须显式登记才放行只读调用。
 * 0.10.0 的 Tool.Annotations 没有 readOnlyHint，因此这里不接受任何外部声明。
 */
final class ToolWriteClassifier {

    private final Set<String> readOnlyTools;

    ToolWriteClassifier(Set<String> readOnlyTools) {
        this.readOnlyTools = Set.copyOf(readOnlyTools == null ? Set.of() : readOnlyTools);
    }

    /**
     * @return true 表示该工具是写工具，图必须拒绝 / 走人工确认
     */
    boolean isWriteTool(String toolName) {
        return !readOnlyTools.contains(toolName);
    }
}
