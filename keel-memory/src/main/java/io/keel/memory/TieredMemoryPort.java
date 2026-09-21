package io.keel.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.KeelPrincipal;
import io.keel.core.MemoryBudget;
import io.keel.core.MemoryFragment;
import io.keel.core.MemoryLayer;
import io.keel.graph.spi.MemoryPort;

/**
 * 三层记忆组合端口（FR-51/FR-52/FR-54）：组合 L2 情景 + L3 长期记忆。
 *
 * <ul>
 *   <li>召回：L3 稳定偏好优先、L2 情景次之，统一受 {@link MemoryBudget} 约束（条数 + 单条截断）；</li>
 *   <li>写入：请求结束后 best-effort 追加一条 L2 情景摘要（输入 + 状态 + 答复）；</li>
 *   <li>隔离：全部按 principal 的 tenant + subject 读取，store 保证不跨层混写（FR-53）。</li>
 * </ul>
 */
public final class TieredMemoryPort implements MemoryPort {

    private static final int RECORD_MAX_CHARS = 1000;

    private final EpisodicStore episodicStore;
    private final LongTermStore longTermStore;

    public TieredMemoryPort(EpisodicStore episodicStore, LongTermStore longTermStore) {
        this.episodicStore = Objects.requireNonNull(episodicStore, "episodicStore");
        this.longTermStore = Objects.requireNonNull(longTermStore, "longTermStore");
    }

    @Override
    public List<MemoryFragment> recall(AgentRequest request, MemoryBudget budget) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(budget, "budget");
        KeelPrincipal principal = request.getPrincipal();
        // 防御：无身份请求（正常路径由 Builder/readObject 保证非空）不召回任何记忆
        if (principal == null) {
            return List.of();
        }

        // FR-60：先取租户级共享记忆（resourceId="*"），再按 principal.resourceIds 取 resource 级
        int perScope = budget.getMaxFragments();
        // FR-52：L3 优先语义召回（向量后端按 query 相似度排序；非向量实现退化为时间序）
        List<MemoryFragment> longTerm = new ArrayList<>(longTermStore.semanticSearch(
                principal.getTenantId(), principal.getSubjectId(),
                request.getInput(), perScope));
        List<MemoryFragment> episodic = new ArrayList<>(episodicStore.recent(
                principal.getTenantId(), principal.getSubjectId(), "*", perScope));

        // 补充 resource 级记忆（若有具体 resourceIds）
        for (String resourceId : principal.getResourceIds()) {
            if (longTerm.size() + episodic.size() >= perScope * 2) {
                break;
            }
            longTerm.addAll(longTermStore.search(
                    principal.getTenantId(), principal.getSubjectId(), resourceId, perScope));
            episodic.addAll(episodicStore.recent(
                    principal.getTenantId(), principal.getSubjectId(), resourceId, perScope));
        }

        // L3 稳定偏好优先，L2 情景次之；统一受预算约束
        List<MemoryFragment> merged = new ArrayList<>(longTerm.size() + episodic.size());
        merged.addAll(longTerm);
        merged.addAll(episodic);

        List<MemoryFragment> result = new ArrayList<>();
        for (MemoryFragment fragment : merged) {
            if (result.size() >= budget.getMaxFragments()) {
                break;
            }
            result.add(truncate(fragment, budget.getMaxCharsPerFragment()));
        }
        return result;
    }

    @Override
    public void record(AgentRequest request, AgentResult result) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        KeelPrincipal principal = request.getPrincipal();
        // 防御：无身份请求（正常路径由 Builder/readObject 保证非空）不落记忆
        if (principal == null) {
            return;
        }
        String summary = summarize(request, result);
        if (summary.length() > RECORD_MAX_CHARS) {
            summary = summary.substring(0, RECORD_MAX_CHARS);
        }
        episodicStore.append(new MemoryFragment(
                MemoryLayer.L2,
                principal.getTenantId(),
                principal.getSubjectId(),
                null,
                summary,
                Instant.now()));
    }

    private static MemoryFragment truncate(MemoryFragment fragment, int maxChars) {
        if (fragment.getContent().length() <= maxChars) {
            return fragment;
        }
        return new MemoryFragment(
                fragment.getLayer(),
                fragment.getTenantId(),
                fragment.getSubjectId(),
                fragment.getKey(),
                fragment.getContent().substring(0, maxChars) + "...",
                fragment.getCreatedAt());
    }

    private static String summarize(AgentRequest request, AgentResult result) {
        String skill = request.getSkill() == null ? "" : request.getSkill();
        String text = result.getText() == null ? "" : result.getText();
        return "[skill=" + skill + "] " + request.getInput()
                + " -> " + result.getStatus() + ": " + text;
    }
}
