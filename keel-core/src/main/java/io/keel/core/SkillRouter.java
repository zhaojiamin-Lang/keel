package io.keel.core;

import java.util.List;
import java.util.Optional;

/**
 * FR-21：Skill 自动路由端口。按「用户输入 + 上下文」从候选 skill 中选择最匹配的，
 * 低置信回退默认只读问答（返回 empty）。
 *
 * <p>路由优先级（由 {@code io.keel.graph.GraphKeelAgent} 保证）：</p>
 * <ol>
 *   <li>显式指定：{@code request.getSkill()} 非空时直接用，不走路由；</li>
 *   <li>自动路由：调用 {@link #route}，按置信度选择；</li>
 *   <li>回退 chat：置信度低于阈值或无候选，返回 empty。</li>
 * </ol>
 *
 * <p>放在 keel-core，keel-graph 和 keel-skill 都能引用，无循环依赖。</p>
 */
public interface SkillRouter {

    /**
     * 按用户输入 + 上下文从候选 skill 中选择最匹配的。
     *
     * @param request 当前请求（含 input / principal / context）
     * @param candidates 候选 skill 列表（不含 chat 默认）
     * @return 路由结果，empty 表示低置信回退 chat
     */
    Optional<RoutingResult> route(AgentRequest request, List<SkillDefinition> candidates);

    /** 路由结果：命中的 skill + 置信度（0.0~1.0）+ 命中规则说明 */
    record RoutingResult(SkillDefinition skill, double confidence, String reason) {

        public RoutingResult {
            if (skill == null) {
                throw new IllegalArgumentException("skill must not be null");
            }
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be in [0.0, 1.0]");
            }
        }
    }
}