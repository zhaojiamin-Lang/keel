package io.keel.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;

/**
 * SubjectAllowlistGrayPolicy（§8 第三期）：tenant / subject 白名单精确命中灰度。
 */
class SubjectAllowlistGrayPolicyTest {

    private static SkillDefinition skill(String name, String version) {
        return SkillDefinition.builder()
                .name(name)
                .description("desc")
                .version(version)
                .build();
    }

    private static AgentRequest requestFor(String tenant, String subject) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal(tenant, subject, List.of()))
                .skill("ticket")
                .input("help")
                .build();
    }

    @Test
    void whitelistedSubjectGetsGrayVersion() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0")));
        SubjectAllowlistGrayPolicy policy = new SubjectAllowlistGrayPolicy(
                registry, List.of(), List.of("user-1"));

        assertThat(policy.select(requestFor("tenant-1", "user-1"), skill("ticket", "1.0")))
                .hasValueSatisfying(g -> assertThat(g.getVersion()).isEqualTo("2.0"));
    }

    @Test
    void whitelistedTenantGetsGrayVersion() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0")));
        SubjectAllowlistGrayPolicy policy = new SubjectAllowlistGrayPolicy(
                registry, List.of("tenant-9"), List.of());

        assertThat(policy.select(requestFor("tenant-9", "anyone"), skill("ticket", "1.0")))
                .isPresent();
    }

    @Test
    void nonWhitelistedPrincipalKeepsStableVersion() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0")));
        SubjectAllowlistGrayPolicy policy = new SubjectAllowlistGrayPolicy(
                registry, List.of("tenant-9"), List.of("user-1"));

        assertThat(policy.select(requestFor("tenant-1", "user-2"), skill("ticket", "1.0")))
                .isEmpty();
    }

    @Test
    void matchingIsExactNotWildcard() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0")));
        // user-1 不应前缀匹配 user-10——灰度名单宁可手动维护，不可静默匹配错误的人
        SubjectAllowlistGrayPolicy policy = new SubjectAllowlistGrayPolicy(
                registry, List.of(), List.of("user-1"));

        assertThat(policy.select(requestFor("tenant-1", "user-10"), skill("ticket", "1.0")))
                .isEmpty();
    }

    @Test
    void emptyAllowlistsDisableGray() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0")));
        SubjectAllowlistGrayPolicy policy = new SubjectAllowlistGrayPolicy(
                registry, List.of(), List.of());

        assertThat(policy.select(requestFor("tenant-1", "user-1"), skill("ticket", "1.0")))
                .isEmpty();
    }
}
