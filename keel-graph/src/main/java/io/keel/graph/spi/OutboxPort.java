package io.keel.graph.spi;

import io.keel.core.OutboxRecord;

/**
 * Outbox 钩子端口（NFR-03：写路径建议 Outbox 钩子）。
 *
 * <p>确认执行的写工具真正运行后，图内核调用本端口，把写动作的完整上下文
 * 交给业务。业务实现在自己的本地事务里把记录落 outbox 表，再由业务侧
 * 投递下游——Keel 不建表、不接管业务事务（需求 §2.2 / §7.2）。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>幂等跳过的写动作（observations 已有同工具记录）不会回调，业务不会
 *       收到重复记录（FR-32：重试不得产生第二笔副作用）；</li>
 *   <li>回调抛出的异常由图内核吞掉、不击穿请求——写已执行无法回滚，
 *       业务实现内部应自行保证落库可靠；</li>
 *   <li>未注册 OutboxPort 时该钩子静默跳过，零负担。</li>
 * </ul>
 */
public interface OutboxPort {

    void record(OutboxRecord record);
}
