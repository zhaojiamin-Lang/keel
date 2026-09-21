package io.keel.graph.spi;

import io.keel.graph.LoopState;
import io.keel.graph.Verdict;

/**
 * Guard 规则端口：业务/平台可在此扩展代码化规则（FR-64，如发布冻结、写次数预算）。
 *
 * <p>注意：skill 是否可写、工具是否在 allowlist 这类硬规则由图节点直接执行，
 * 不依赖本端口；本端口缺省放行，仅供扩展。</p>
 */
public interface GuardPort {

    Verdict inspect(LoopState state);

    static GuardPort allowAll() {
        return state -> Verdict.approved();
    }
}
