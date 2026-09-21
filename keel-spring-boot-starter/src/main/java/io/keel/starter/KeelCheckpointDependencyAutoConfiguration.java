package io.keel.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.keel.graph.spi.CheckpointPort;

/**
 * Checkpoint 依赖校验（FR-12 生产化）：classpath 上已有 {@link CheckpointPort} 接口，
 * 容器里却<b>既没有 Redis 实现也没有业务自备的 bean</b> 时，启动期直接失败。
 *
 * <p><b>为什么不能静默降级成内存实现：</b>写确认链路（FR-13）依赖 checkpoint 恢复
 * 待确认动作。内存实现只在单实例内可见——多实例部署时用户点「确认」的请求可能落到
 * 另一个实例，恢复不到 PendingAction，表现为<b>确认点了没反应、且没有任何报错</b>。
 * 这类静默失败比启动报错危险得多，所以这里选择 fail-fast 并给出可操作的修复指引。</p>
 *
 * <p><b>为什么这是个独立的自动配置类（而不是给 Redis 装配加条件）：</b>
 * 「Redis 实现不在 classpath」和「业务自备了别的实现」是两件事，前者需要报错、后者需要放行。
 * 把校验单独成类，才能把「让位」表达成 {@link ConditionalOnMissingBean}，
 * 而不是在 Redis 装配里堆猜测。</p>
 *
 * <p>三个前提都靠条件表达：</p>
 * <ul>
 *   <li>{@link ConditionalOnClass}：classpath 没有 {@link CheckpointPort} 时整类不装配
 *       ——{@code keel.checkpoint.enabled} 对「没有 checkpoint 能力」的项目没有意义，
 *       不该用默认值把它们拦下（NFR-04：可选能力缺失不阻塞启动）。</li>
 *   <li>{@link ConditionalOnMissingClass}：已有 Redis 实现时不装配，交给
 *       {@code KeelCheckpointAutoConfiguration} 正常建 bean。</li>
 *   <li>{@link ConditionalOnMissingBean}：业务自备 {@link CheckpointPort} 时让位
 *       ——业务已对自己的实现（含多实例适用性）负责。</li>
 * </ul>
 *
 * <p>两个 {@code @ConditionalOnMissingBean} 都放在 <b>bean 方法</b>上（放在类上时，
 * 自动配置解析期本类自身的 bean 定义可能已进入候选，判定结果不稳）：一个盯
 * {@link CheckpointPort}（有没有别的实现），一个盯校验器自身类型（防重复注册）。</p>
 *
 * <p><b>已知边界（刻意保守，不在这里扩大拦截面）：</b>本校验判断不了「这个项目到底会不会
 * 真的走会话持久化」——它只看「有没有实现」与「开关是否打开」。真正的失败模式是
 * {@code GraphKeelAgent} 在 {@code confirmedActionId} 非空而 {@code checkpointPort == null}
 * 时返回 {@code CONFIRM_REQUIRES_SESSION}，那是运行期 fail-<b>closed</b>，比启动期猜测可靠。
 * 要把启动期拦住做实，需要 graph 装配提供一个「会话必然派生 runId」的守卫 bean，
 * 届时本类改为 {@code @ConditionalOnBean} 它——测试里
 * {@code failsFastForGraphAssemblyThatReallyCheckpointsSessions} 就是这个缺口的显式占位。</p>
 */
@AutoConfiguration
@ConditionalOnClass(CheckpointPort.class)
@ConditionalOnProperty(
        prefix = "keel.checkpoint",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnMissingClass("io.keel.memory.RedisCheckpointPort")
@EnableConfigurationProperties(KeelProperties.class)
public class KeelCheckpointDependencyAutoConfiguration {

    /**
     * 校验器：启动期把「开了 checkpoint 但没有任何实现」这个组合拦下来。
     *
     * <p>只写 {@code @ConditionalOnMissingBean(CheckpointPort.class)}——即「容器里已有业务
     * 自备的实现就整体让位」。<b>刻意不把校验器自身类型也列进去</b>：那样做会让本类在
     * 只装配自己（没有别的 CheckpointPort bean）时把自身定义也算成候选而自我否决，
     * 结果是校验静默失效、启动照常成功——正是最不该发生的那种「看起来配好了」。</p>
     */
    @Bean
    @ConditionalOnMissingBean(CheckpointPort.class)
    public CheckpointDependencyValidator keelCheckpointDependencyValidator(
            KeelProperties properties) {
        return new CheckpointDependencyValidator(properties);
    }

    /** 校验器：只做一件事——在启动期把「开了 checkpoint 但没实现」这个组合拦下来。 */
    public static class CheckpointDependencyValidator {

        CheckpointDependencyValidator(KeelProperties properties) {
            if (!properties.getCheckpoint().isEnabled()) {
                // 显式关闭 checkpoint：单实例内存实现可接受，不校验。
                // 类上的 @ConditionalOnProperty 已保证走到这里时通常是 true，
                // 这条分支是为 Bean 被手工 new 的场景留的对称保护。
                return;
            }
            throw new IllegalStateException(
                    "keel.checkpoint.enabled=true（默认）但 classpath 上没有 RedisCheckpointPort，"
                            + "写确认（FR-13）依赖 checkpoint 恢复待确认动作，缺失会导致确认链路静默失效。"
                            + "请二选一：\n"
                            + "  1) 引入 io.keel:keel-memory 依赖并配置 keel.checkpoint.host / port"
                            + "（生产必需：多实例共享同一 checkpoint）；\n"
                            + "  2) 不用 checkpoint 时显式设 keel.checkpoint.enabled=false；"
                            + "想用内存实现则自备一个 CheckpointPort bean"
                            + "（单实例可用，多实例不适用）。");
        }
    }
}
