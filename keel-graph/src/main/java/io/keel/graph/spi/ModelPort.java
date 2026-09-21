package io.keel.graph.spi;

import io.keel.graph.LoopState;
import io.keel.graph.PlanDecision;
import io.keel.graph.Verdict;

/**
 * 模型端口：图内核不绑定任何模型 SDK（后续由 keel-model-spring-ai 适配）。
 *
 * <ul>
 *     <li>{@link #plan}：基于当前状态决定下一步（回答 / 检索 / 调工具 / 提议写）；</li>
 *     <li>{@link #critique}：对草稿答案做 Critic 裁决，仅作为代码硬规则之外的补充，
 *     安全判定不允许只靠模型（铁律 4）。</li>
 * </ul>
 */
public interface ModelPort {

    PlanDecision plan(LoopState state);

    Verdict critique(LoopState state);
}
