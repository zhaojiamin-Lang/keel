package io.keel.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;

/**
 * PercentGrayPolicy（§8 第三期）：
 * <ol>
 *     <li>确定性：同一 principal + skill 反复请求命中同一版本（sticky）；</li>
 *     <li>percent=0 / 无灰度候选 / 无 principal 时返回稳定版；</li>
 *     <li>percent=100 全量灰度；</li>
 *     <li>percent 越界启动期拒绝。</li>
 * </ol>
 */
class PercentGrayPolicyTest {

    private static final KeelPrincipal PRINCIPAL =
            new KeelPrincipal("tenant-1", "user-1", List.of());

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
    void selectionIsStickyForSamePrincipalAndSkill() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0")));
        PercentGrayPolicy policy = new PercentGrayPolicy(registry, 100);

        SkillDefinition first = policy.select(
                requestFor("tenant-1", "user-1"), skill("ticket", "1.0")).orElseThrow();
        for (int i = 0; i < 10; i++) {
            SkillDefinition again = policy.select(
                    requestFor("tenant-1", "user-1"), skill("ticket", "1.0")).orElseThrow();
            // 同一 principal + skill 必须永远命中同一版本，不允许灰度/稳定抖动
            assertThat(again.getVersion())
                    .as("第 %d 次请求应与首次命中一致", i)
                    .isEqualTo(first.getVersion());
        }
    }

    @Test
    void zeroPercentKeepsStableVersion() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0")));
        PercentGrayPolicy policy = new PercentGrayPolicy(registry, 0);

        assertThat(policy.select(requestFor("tenant-1", "user-1"), skill("ticket", "1.0")))
                .isEmpty();
    }

    @Test
    void fullPercentSendsEveryPrincipalToGray() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0")));
        PercentGrayPolicy policy = new PercentGrayPolicy(registry, 100);

        for (int i = 0; i < 20; i++) {
            AgentRequest request = requestFor("tenant-" + i, "user-" + i);
            assertThat(policy.select(request, skill("ticket", "1.0")))
                    .as("percent=100 时所有用户都应命中灰度")
                    .isPresent()
                    .hasValueSatisfying(g -> assertThat(g.getVersion()).isEqualTo("2.0"));
        }
    }

    @Test
    void skillWithoutGrayCandidateKeepsStable() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0"), skill("wiki", "1.0")),
                List.of(skill("ticket", "2.0")));
        PercentGrayPolicy policy = new PercentGrayPolicy(registry, 100);

        assertThat(policy.select(
                requestFor("tenant-1", "user-1"), skill("wiki", "1.0"))).isEmpty();
    }

    @Test
    void percentOutOfRangeFailsFast() {
        SkillRegistry registry = new SkillRegistry(
                List.of(skill("ticket", "1.0")),
                List.of(skill("ticket", "2.0")));

        assertThatThrownBy(() -> new PercentGrayPolicy(registry, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PercentGrayPolicy(registry, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
