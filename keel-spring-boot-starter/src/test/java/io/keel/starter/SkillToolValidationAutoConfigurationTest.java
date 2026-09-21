package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.keel.skill.SkillRegistry;

/**
 * FR-23 启动期 Tool 校验回归测试。
 *
 * <p>opt-in 模式：业务显式配 keel.tools.allowed 后，skill 声明的工具必须在白名单内，
 * 否则 fail-closed 启动失败。白名单为空时跳过校验（零配置仍能启动）。
 * 运行期 PlanNode 的拦截是防御纵深，这里保证问题在启动时就暴露。</p>
 */
class SkillToolValidationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KeelAutoConfiguration.class);

    @Test
    void contextStartsWhenSkillToolsAreAllowed() {
        // 显式配齐白名单——ticket-ops(issue.create)、mcp-query(search_orders)、
        // mcp-multi(gitlab.search_issues + oa.list_approvals) 全部覆盖
        runner
                .withPropertyValues(
                        "keel.tools.allowed[0]=issue.create",
                        "keel.tools.allowed[1]=search_orders",
                        "keel.tools.allowed[2]=gitlab.search_issues",
                        "keel.tools.allowed[3]=oa.list_approvals")
                .run(context -> assertThat(context).hasSingleBean(SkillRegistry.class));
    }

    @Test
    void contextStartsWithoutWhitelistBecauseOptIn() {
        // 不配 keel.tools.allowed（默认空）——opt-in 模式跳过校验，启动成功
        runner.run(context -> assertThat(context).hasSingleBean(SkillRegistry.class));
    }

    @Test
    void contextFailsWhenWhitelistMissingDeclaredTool() {
        // 只配 search_orders——ticket-ops 声明 issue.create 未在白名单 → fail-closed
        runner
                .withPropertyValues("keel.tools.allowed[0]=search_orders")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not allowed"));
    }
}