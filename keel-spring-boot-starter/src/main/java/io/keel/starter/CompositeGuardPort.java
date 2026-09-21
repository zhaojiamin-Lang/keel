package io.keel.starter;

import java.util.List;
import java.util.Objects;

import io.keel.graph.LoopState;
import io.keel.graph.Verdict;
import io.keel.graph.spi.GuardPort;
import io.keel.guard.InjectionGuard;

/**
 * FR-64：组合多个 GuardPort，任一拒绝即拒绝。
 *
 * <p>裁决优先级（按顺序短路）：</p>
 * <ol>
 *   <li>{@link ResourceIdGuardPort}（FR-61）：编造业务 ID → 硬拒绝；</li>
 *   <li>{@link CitationGuardPort}（FR-42）：无引用结论 → 降级 UNCERTAIN；</li>
 *   <li>{@link InjectionGuard}（FR-62）：untrusted 内容命中注入话术且有写动作 → 硬拒绝。</li>
 * </ol>
 * <p>前者是安全违规（rejected），后者是信息不足（downgrade）。
 * rejected 优先于 downgrade 短路——编造 ID 比缺引用更严重，先拦。</p>
 */
public final class CompositeGuardPort implements GuardPort {

    private final List<GuardPort> delegates;

    public CompositeGuardPort(GuardPort... delegates) {
        this.delegates = List.of(delegates);
    }

    @Override
    public Verdict inspect(LoopState state) {
        Verdict firstDowngrade = null;
        for (GuardPort delegate : delegates) {
            Verdict verdict = delegate.inspect(state);
            Objects.requireNonNull(verdict, "GuardPort 不得返回 null: " + delegate);
            // rejected（硬拒绝）立即短路——安全违规优先
            if (!verdict.isApproved() && !verdict.isDowngrade()) {
                return verdict;
            }
            // 记录第一个 downgrade，若后续无 rejected 则返回它
            if (firstDowngrade == null && verdict.isDowngrade()) {
                firstDowngrade = verdict;
            }
        }
        return firstDowngrade != null ? firstDowngrade : Verdict.approved();
    }
}