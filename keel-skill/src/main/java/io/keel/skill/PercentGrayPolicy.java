package io.keel.skill;

import java.util.Objects;
import java.util.Optional;

import io.keel.core.AgentRequest;
import io.keel.core.SkillDefinition;
import io.keel.core.SkillGrayPolicy;

/**
 * 百分比灰度（默认实现一）：对 {@code hash(tenant|subject|skill) % 100 < percent}
 * 的请求返回灰度版本。
 *
 * <p>hash 用 {@link String#hashCode()}——其算法在 Java 规范中固定，跨 JVM、
 * 跨重启结果一致，天然满足灰度的 sticky 要求：同一用户对同一 skill 永远命中
 * 同一版本，不会在灰度/稳定之间抖动（观测与回滚才有意义）。</p>
 *
 * <p>percent=0 等价关闭灰度；percent=100 等价全量灰度。无灰度候选的 skill
 * 一律返回 empty（稳定版），percent 对其无意义。</p>
 */
public final class PercentGrayPolicy implements SkillGrayPolicy {

    private final SkillRegistry registry;
    private final int percent;

    public PercentGrayPolicy(SkillRegistry registry, int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent 必须在 [0, 100]: " + percent);
        }
        this.registry = Objects.requireNonNull(registry, "registry");
        this.percent = percent;
    }

    @Override
    public Optional<SkillDefinition> select(AgentRequest request, SkillDefinition stable) {
        if (request == null || stable == null || percent == 0) {
            return Optional.empty();
        }
        Optional<SkillDefinition> gray = registry.grayVersion(stable.getName());
        if (gray.isEmpty()) {
            return Optional.empty();
        }
        // 加 skill 名做盐，避免不同 skill 的灰度桶对同一用户完全对齐
        String salt = stable.getName() + "#";
        int bucket = Math.floorMod(
                (salt + principalKey(request)).hashCode(), 100);
        return bucket < percent ? gray : Optional.empty();
    }

    private static String principalKey(AgentRequest request) {
        // principal 缺失（无主请求）不参与灰度——灰度对象是"人"，不是匿名流量
        if (request.getPrincipal() == null) {
            return "";
        }
        String tenant = nullToEmpty(request.getPrincipal().getTenantId());
        String subject = nullToEmpty(request.getPrincipal().getSubjectId());
        return tenant + "|" + subject;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
