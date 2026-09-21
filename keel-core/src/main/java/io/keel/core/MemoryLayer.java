package io.keel.core;

/**
 * 记忆层级（FR-50/FR-51/FR-52）。
 *
 * <ul>
 *   <li>{@link #L2} 情景记忆：会话/任务级轨迹与摘要，默认 Mongo；</li>
 *   <li>{@link #L3} 长期记忆：稳定偏好/规范摘要，PG 或向量，需可撤销。</li>
 * </ul>
 * L1 工作记忆由 LoopState + Checkpoint 承载，不进入本枚举。
 */
public enum MemoryLayer {
    L2,
    L3
}
