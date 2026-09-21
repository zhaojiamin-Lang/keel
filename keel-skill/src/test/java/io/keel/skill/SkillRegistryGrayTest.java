package io.keel.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.SkillDefinition;

/**
 * SkillRegistry 灰度共存规则（§8 第三期）：
 * <ol>
 *     <li>同名不同版本可共存（稳定 + 灰度）；</li>
 *     <li>同名同版本拒绝（没有灰度意义）；</li>
 *     <li>灰度版本必须有稳定版本兜底；</li>
 *     <li>每个 skill 至多一个灰度版本；</li>
 *     <li>get/all 只暴露稳定版本，灰度候选仅供策略读取。</li>
 * </ol>
 */
class SkillRegistryGrayTest {

    private static SkillDefinition skill(String name, String version) {
        return SkillDefinition.builder()
                .name(name)
                .description("desc of " + name)
                .version(version)
                .build();
    }

    @Test
    void grayVersionCoexistsWithStableWhenVersionsDiffer() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0")));

        assertThat(registry.get("ticket")).hasValueSatisfying(
                s -> assertThat(s.getVersion()).isEqualTo("1.0"));
        assertThat(registry.grayVersion("ticket")).hasValueSatisfying(
                g -> assertThat(g.getVersion()).isEqualTo("2.0"));
    }

    @Test
    void grayVersionEqualToStableIsRejected() {
        assertThatThrownBy(() -> new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "1.0"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("equals stable version");
    }

    @Test
    void grayVersionWithoutStableIsRejected() {
        assertThatThrownBy(() -> new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ghost", "2.0"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no stable version");
    }

    @Test
    void multipleGrayVersionsForSameSkillAreRejected() {
        assertThatThrownBy(() -> new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0"), skill("ticket", "3.0"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple gray versions");
    }

    @Test
    void graySkillsAreNeverRoutingCandidates() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0"), skill("wiki", "1.0")),
                List.of(skill("ticket", "2.0")));

        // all() 只含稳定版本——灰度版本绝不能进入自动路由候选，
        // 否则会被 SkillRouter 直接选中、绕过灰度策略
        assertThat(registry.all())
                .extracting(SkillDefinition::getName)
                .containsExactly("ticket", "wiki");
    }

    @Test
    void unknownSkillHasNoGrayVersion() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0")));

        assertThat(registry.grayVersion("unknown")).isEmpty();
        assertThat(registry.grayVersion(null)).isEmpty();
    }
}
