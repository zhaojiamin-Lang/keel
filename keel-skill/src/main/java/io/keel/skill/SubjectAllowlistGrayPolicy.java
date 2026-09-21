package io.keel.skill;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.keel.core.AgentRequest;
import io.keel.core.SkillDefinition;
import io.keel.core.SkillGrayPolicy;

/**
 * 白名单灰度（默认实现二）：tenant 或 subject 命中显式白名单时返回灰度版本。
 *
 * <p>适用场景：内部同事 / 种子租户先行验证。白名单是精确匹配（大小写敏感），
 * 不做通配符——灰度名单宁可手动维护，不可静默匹配错误的人。</p>
 *
 * <p>语义：tenant 名单与 subject 名单取并集；两者都为空时等价关闭灰度。</p>
 */
public final class SubjectAllowlistGrayPolicy implements SkillGrayPolicy {

    private final SkillRegistry registry;
    private final List<String> tenants;
    private final List<String> subjects;

    public SubjectAllowlistGrayPolicy(SkillRegistry registry,
            List<String> tenants, List<String> subjects) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tenants = tenants == null ? List.of() : List.copyOf(tenants);
        this.subjects = subjects == null ? List.of() : List.copyOf(subjects);
    }

    @Override
    public Optional<SkillDefinition> select(AgentRequest request, SkillDefinition stable) {
        if (request == null || stable == null
                || (tenants.isEmpty() && subjects.isEmpty())) {
            return Optional.empty();
        }
        Optional<SkillDefinition> gray = registry.grayVersion(stable.getName());
        if (gray.isEmpty() || request.getPrincipal() == null) {
            return Optional.empty();
        }
        String tenant = request.getPrincipal().getTenantId();
        String subject = request.getPrincipal().getSubjectId();
        boolean hit = (tenant != null && tenants.contains(tenant))
                || (subject != null && subjects.contains(subject));
        return hit ? gray : Optional.empty();
    }
}
