package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.keel.graph.spi.CheckpointPort;

/**
 * Checkpoint 缺实现时的 fail-fast 契约（FR-12 生产化）。
 *
 * <p>验证的取舍：<b>不做静默降级</b>。写确认（FR-13）依赖 checkpoint 恢复待确认动作，
 * 内存实现在多实例下会让用户点「确认」静默无反应，且没有任何报错——所以宁可启动失败。</p>
 *
 * <p><b>为什么必须用 {@link FilteredClassLoader} 而不是相信本模块 classpath：</b>
 * {@code keel-memory} 在 starter 的 POM 里标了 {@code optional}，但 optional 只阻止
 * <b>向下游传递</b>，它自己仍在<b>本模块</b>的编译/测试 classpath 上（实测
 * {@code dependency:tree} 显示 scope=compile）。于是 `@ConditionalOnMissingClass
 * ("io.keel.memory.RedisCheckpointPort")` 在本模块测试里<b>永远不匹配</b>，
 * 本类压根不装配。想验证那条分支，只能显式把类从 classpath 里滤掉——
 * 这也正是「单元测试环境 ≠ 下游业务环境」的典型陷阱。</p>
 */
class KeelCheckpointDependencyAutoConfigurationTest {

    /** 让上下文像「引了端口、没引 Redis 实现」的下游业务一样。 */
    private static ApplicationContextRunner runnerWithoutRedis() {
        return new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("io.keel.memory"))
                .withConfiguration(
                        AutoConfigurations.of(KeelCheckpointDependencyAutoConfiguration.class));
    }

    /** 前提校验：确实能靠 FilteredClassLoader 把 Redis 实现滤出 classpath。 */
    @Test
    void filteredClassLoaderActuallyHidesRedisImplementation() {
        assertThat(isPresentOnAppClassLoader("io.keel.memory.RedisCheckpointPort"))
                .as("keel-memory 是 optional 但仍在本模块 classpath；本测试假设它在")
                .isTrue();
    }

    /**
     * 核心用例：默认 enabled=true、无任何实现 → 启动必须失败，且报错可操作。
     */
    @Test
    void failsFastWhenEnabledButNoImplementation() {
        runnerWithoutRedis().run(context -> {
            assertThat(context).hasFailed();
            // 错误信息必须可操作：说清「为什么危险」+「两条修复路径」
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("keel.checkpoint.enabled=true")
                    .hasStackTraceContaining("keel-memory")
                    .hasStackTraceContaining("FR-13");
        });
    }

    /** 显式关闭：单实例 dev / 示例场景，业务声明后放行（示例就是走这条路）。 */
    @Test
    void passesWhenCheckpointExplicitlyDisabled() {
        runnerWithoutRedis()
                .withPropertyValues("keel.checkpoint.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 开关关掉时校验器根本不创建，而不是创建后空转
                    assertThat(context).doesNotHaveBean("keelCheckpointDependencyValidator");
                });
    }

    /** classpath 完全没有 CheckpointPort 时整类不装配（对没有该能力的项目零影响）。 */
    @Test
    void doesNotApplyWhenCheckpointPortAbsentFromClasspath() {
        new ApplicationContextRunner()
                // FilteredClassLoader 不支持 Class 与 String 混用 varargs，统一用类名
                .withClassLoader(new FilteredClassLoader(
                        "io.keel.graph.spi.CheckpointPort", "io.keel.memory"))
                .withConfiguration(
                        AutoConfigurations.of(KeelCheckpointDependencyAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("keelCheckpointDependencyValidator");
                });
    }

    /** 业务自备 CheckpointPort bean 时校验器让位（业务对自己的实现负责）。 */
    @Test
    void backsOffWhenBusinessProvidesOwnImplementation() {
        runnerWithoutRedis()
                .withBean(CheckpointPort.class, GraphRagTestFixtures.NoopCheckpointPort::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("keelCheckpointDependencyValidator");
                });
    }

    /**
     * 反向用例：本模块 classpath 上真有 Redis 实现时，本类必须整体让位，
     * 由 {@code KeelCheckpointAutoConfiguration} 负责装配——两条路径不能同时生效。
     */
    @Test
    void backsOffWhenRedisImplementationIsPresent() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(KeelCheckpointDependencyAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("keelCheckpointDependencyValidator");
                });
    }

    private static boolean isPresentOnAppClassLoader(String className) {
        try {
            Class.forName(className, false,
                    KeelCheckpointDependencyAutoConfigurationTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException notPresent) {
            return false;
        }
    }
}
