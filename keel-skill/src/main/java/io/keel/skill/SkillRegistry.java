package io.keel.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.keel.core.SkillDefinition;

/**
 * Skill 注册表：稳定版本 + 可选灰度版本（§8 第三期）。
 *
 * <p>注册规则（fail-closed，启动期暴露问题）：</p>
 * <ul>
 *   <li>稳定版本：同名即冲突（既有行为不变）；</li>
 *   <li>灰度版本：必须与某个稳定版本同名且<strong>版本号不同</strong>——同名同版本
 *       没有灰度意义，直接拒绝；每个 skill 至多一个灰度版本（多灰度分批属于后续
 *       运营能力，当前语义复杂度不值当）。</li>
 * </ul>
 *
 * <p>隔离边界：{@link #get(String)} 与 {@link #all()} 只暴露稳定版本——灰度版本
 * 绝不进入自动路由候选，否则会被 {@code SkillRouter} 直接选中、绕过灰度策略；
 * 灰度命中必须经过 {@link io.keel.core.SkillGrayPolicy} 的确定性判定。
 * {@link #grayVersion(String)} 仅供灰度策略实现读取候选。</p>
 */
public final class SkillRegistry {

    private final Map<String, SkillDefinition> skills;
    private final Map<String, SkillDefinition> graySkills;

    public SkillRegistry(List<SkillDefinition> definitions) {
        this(definitions, List.of());
    }

    /**
     * @param stableSkills 稳定版本（同名冲突即抛异常）
     * @param graySkills   灰度版本（同名不同版本，至多一个/skill）
     */
    public SkillRegistry(List<SkillDefinition> stableSkills, List<SkillDefinition> graySkills) {
        Objects.requireNonNull(stableSkills, "stableSkills");
        Objects.requireNonNull(graySkills, "graySkills");

        Map<String, SkillDefinition> index = new LinkedHashMap<>();
        for (SkillDefinition definition : stableSkills) {
            if (definition == null) {
                continue;
            }
            if (index.containsKey(definition.getName())) {
                throw new IllegalStateException(
                        "Duplicate skill name: " + definition.getName());
            }
            index.put(definition.getName(), definition);
        }
        this.skills = Collections.unmodifiableMap(index);

        Map<String, SkillDefinition> grayIndex = new LinkedHashMap<>();
        for (SkillDefinition definition : graySkills) {
            if (definition == null) {
                continue;
            }
            String name = definition.getName();
            SkillDefinition stable = index.get(name);
            // 灰度版本必须有对应的稳定版本兜底：灰度回滚 = 沿用稳定版，不能悬空
            if (stable == null) {
                throw new IllegalStateException(
                        "Gray skill has no stable version: " + name);
            }
            if (stable.getVersion().equals(definition.getVersion())) {
                throw new IllegalStateException(
                        "Gray skill version equals stable version: "
                                + name + ":" + definition.getVersion());
            }
            if (grayIndex.containsKey(name)) {
                throw new IllegalStateException(
                        "Multiple gray versions for skill: " + name);
            }
            grayIndex.put(name, definition);
        }
        this.graySkills = Collections.unmodifiableMap(grayIndex);
    }

    /** 稳定版本解析（不含灰度——灰度命中必须走 SkillGrayPolicy）。 */
    public Optional<SkillDefinition> get(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(skills.get(name));
    }

    /** 稳定版本全集（自动路由候选；不含灰度）。 */
    public List<SkillDefinition> all() {
        return List.copyOf(skills.values());
    }

    /** 该 skill 的灰度版本候选（至多一个）；无灰度时 empty。供灰度策略实现读取。 */
    public Optional<SkillDefinition> grayVersion(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(graySkills.get(name));
    }
}
