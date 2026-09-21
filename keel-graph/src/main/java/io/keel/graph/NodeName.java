package io.keel.graph;

/**
 * 默认图（FR-10）中的节点标识：
 *
 * <pre>
 * PLAN -> RETRIEVE/TOOL -> OBSERVE -> PLAN（循环）
 * PLAN -> CRITIC -> GUARD -> END
 * CRITIC 不通过且仍有预算时可回到 PLAN
 * 写提议在 PLAN/TOOL 处被代码拦截，直接走 GUARD，绝不执行（FR-13）
 * </pre>
 */
public enum NodeName {
    PLAN,
    RETRIEVE,
    TOOL,
    OBSERVE,
    CRITIC,
    GUARD,
    END
}
