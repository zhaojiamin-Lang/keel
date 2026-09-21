package io.keel.starter;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import io.keel.core.KeelAgent;
import io.keel.core.MemoryBudget;
import io.keel.core.SkillGrayPolicy;
import io.keel.core.SkillRouter;
import io.keel.graph.GraphKeelAgent;
import io.keel.graph.StepBudget;
import io.keel.graph.spi.CheckpointPort;
import io.keel.graph.spi.GuardPort;
import io.keel.graph.spi.MemoryPort;
import io.keel.graph.spi.OutboxPort;
import io.keel.graph.spi.RetrievalPort;
import io.keel.graph.spi.StateObserver;
import io.keel.graph.spi.ToolPort;
import io.keel.graph.spi.TracePort;
import io.keel.guard.CitationGuard;
import io.keel.guard.InjectionGuard;
import io.keel.model.SingleShotKeelAgent;
import io.keel.model.graph.SpringAiModelPort;
import io.keel.skill.SkillLoader;
import io.keel.skill.KeywordSkillRouter;
import io.keel.skill.SkillRegistry;
import io.keel.skill.ToolCatalog;

@AutoConfiguration
@ConditionalOnClass(ChatModel.class)
@ConditionalOnProperty(
        prefix = "keel.model",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(KeelProperties.class)
public class KeelAutoConfiguration {

    // ==================== single-shot 模式（默认） ====================

    @Bean
    @ConditionalOnBean(ChatModel.class)
    @ConditionalOnProperty(
            prefix = "keel.agent",
            name = "mode",
            havingValue = KeelProperties.MODE_SINGLE_SHOT,
            matchIfMissing = true)
    public SingleShotKeelAgent singleShotKeelAgent(ChatModel chatModel) {
        return new SingleShotKeelAgent(chatModel);
    }

    @Bean
    @Primary
    @ConditionalOnBean(ChatModel.class)
    @ConditionalOnProperty(
            prefix = "keel.agent",
            name = "mode",
            havingValue = KeelProperties.MODE_SINGLE_SHOT,
            matchIfMissing = true)
    public KeelAgent guardedKeelAgent(
            SingleShotKeelAgent delegate,
            SkillRegistry skillRegistry,
            CitationGuard citationGuard) {
        return new GuardedKeelAgent(delegate, skillRegistry, citationGuard);
    }

    // ==================== graph 模式（FR-10/11/13） ====================

    @Bean
    @ConditionalOnBean(ChatModel.class)
    @ConditionalOnProperty(
            prefix = "keel.agent",
            name = "mode",
            havingValue = KeelProperties.MODE_GRAPH)
    public SpringAiModelPort springAiModelPort(ChatModel chatModel) {
        return new SpringAiModelPort(chatModel);
    }

    /**
     * 默认 GuardPort：组合内置规则（FR-64）：ResourceIdGuardPort（FR-61）+ CitationGuardPort（FR-42）
     * + InjectionGuard（FR-62：untrusted 检索内容命中注入话术时拦截写动作）。
     * 业务可自行注册 GuardPort bean 覆盖（如冻结窗口、写次数预算等扩展规则），
     * 此时本默认 bean 不创建——业务自定义时请自行决定是否保留注入防护。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "keel.agent",
            name = "mode",
            havingValue = KeelProperties.MODE_GRAPH)
    @ConditionalOnMissingBean(GuardPort.class)
    public GuardPort keelGuardPort() {
        return new CompositeGuardPort(
                new ResourceIdGuardPort(),
                new CitationGuardPort(),
                new InjectionGuard());
    }

    /**
     * 图执行器：检索 / 工具 / 业务 Guard 端口均为可选——业务没装对应能力时，
     * 只有模型真的请求检索/调工具才会以 NO_*_PORT 失败，纯对话路径不受影响。
     */
    @Bean
    @ConditionalOnBean(ChatModel.class)
    @ConditionalOnProperty(
            prefix = "keel.agent",
            name = "mode",
            havingValue = KeelProperties.MODE_GRAPH)
    public GraphKeelAgent graphKeelAgent(
            SpringAiModelPort springAiModelPort,
            ObjectProvider<RetrievalPort> retrievalPorts,
            ObjectProvider<ToolPort> toolPorts,
            ObjectProvider<GuardPort> guardPorts,
            ObjectProvider<CheckpointPort> checkpointPorts,
            ObjectProvider<TracePort> tracePorts,
            ObjectProvider<MemoryPort> memoryPorts,
            ObjectProvider<OutboxPort> outboxPorts,
            ObjectProvider<SkillGrayPolicy> skillGrayPolicies,
            ObjectProvider<StateObserver> stateObservers,
            SkillRegistry skillRegistry,
            KeelProperties properties) {
        KeelProperties.Agent agent = properties.getAgent();
        KeelProperties.Memory memory = properties.getMemory();
        return GraphKeelAgent.builder()
                .modelPort(springAiModelPort)
                .retrievalPort(retrievalPorts.getIfAvailable())
                .toolPort(toolPorts.getIfAvailable())
                .guardPort(guardPorts.getIfAvailable(GuardPort::allowAll))
                .checkpointPort(checkpointPorts.getIfAvailable())
                .tracePort(tracePorts.getIfAvailable())
                .memoryPort(memoryPorts.getIfAvailable())
                // NFR-03：业务自备 OutboxPort bean 才启用写路径 Outbox 钩子，无 bean 零负担
                .outboxPort(outboxPorts.getIfAvailable())
                // §8 第三期：灰度策略可选——业务配置 keel.skill-gray.* 或自备 bean 才生效，
                // 不配置 = 永远稳定版本，现行为零变化
                .skillGrayPolicy(skillGrayPolicies.getIfAvailable())
                // FR-14：状态观察端口可选——业务自备 StateObserver bean 即零配置接入，
                // 每步执行后收到只读快照；无 bean 时内核不做任何额外工作
                .stateObserver(stateObservers.getIfAvailable())
                .memoryBudget(new MemoryBudget(
                        memory.getMaxFragments(), memory.getMaxCharsPerFragment()))
                .budget(new StepBudget(agent.getMaxSteps(), agent.getTimeout(), agent.getMaxWrites()))
                .skillResolver(skillRegistry::get)
                .skillRouter(new KeywordSkillRouter())
                .skillCandidates(skillRegistry::all)
                .build();
    }

    @Bean
    @Primary
    @ConditionalOnBean(ChatModel.class)
    @ConditionalOnProperty(
            prefix = "keel.agent",
            name = "mode",
            havingValue = KeelProperties.MODE_GRAPH)
    public KeelAgent guardedGraphKeelAgent(
            GraphKeelAgent graphKeelAgent,
            SkillRegistry skillRegistry,
            CitationGuard citationGuard) {
        // 外层 GuardedKeelAgent 负责 skill 解析与 CitationGuard 纵深防御，
        // 图内 GuardPort 是图自己的业务规则扩展点，两者后续再桥接
        return new GuardedKeelAgent(graphKeelAgent, skillRegistry, citationGuard);
    }

    // ==================== 两种模式共用 ====================

    /**
     * FR-23：启动期 Tool 校验——业务显式配置 keel.tools.allowed 后，
     * skill 声明的工具必须在白名单内，否则启动失败（fail-closed）。
     * 白名单为空时跳过校验（opt-in 模式），保证零配置项目仍能启动；
     * 一旦业务显式授权，未授权工具的 skill 在启动时即暴露，不拖到运行期。
     * 运行期 PlanNode 仍保留防御纵深，这里是补强不是替换。
     */
    @Bean
    public SkillRegistry skillRegistry(KeelProperties properties) {
        java.util.List<io.keel.core.SkillDefinition> skills = new SkillLoader().load();
        // §8 第三期：可选灰度目录（skills-gray/，与 skills/ 同格式，同名不同版本）；
        // 目录不存在 = 无灰度，loadOptional 返回空列表，零负担
        java.util.List<io.keel.core.SkillDefinition> graySkills =
                new SkillLoader().loadOptional("skills-gray");
        java.util.List<String> allowed = properties.getTools().getAllowed();
        if (allowed != null && !allowed.isEmpty()) {
            new ToolCatalog(allowed).validate(skills);
            // 灰度版本同受工具白名单约束（FR-23）：灰度不是绕过治理的后门
            new ToolCatalog(allowed).validate(graySkills);
        }
        return new SkillRegistry(skills, graySkills);
    }

    /**
     * Skill 灰度策略（§8 第三期）：仅当 keel.skill-gray.enabled=true 且存在灰度
     * 配置时创建；业务也可注册自己的 {@link SkillGrayPolicy} bean 覆盖（此时本
     * 默认 bean 不创建，与 GuardPort 的覆盖语义一致）。
     * 未知 strategy 直接启动失败（fail-closed），不静默回退成全量灰度。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "keel.skill-gray",
            name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(SkillGrayPolicy.class)
    public SkillGrayPolicy skillGrayPolicy(SkillRegistry skillRegistry,
            KeelProperties properties) {
        KeelProperties.SkillGray config = properties.getSkillGray();
        return switch (config.getStrategy() == null ? "" : config.getStrategy()) {
            case "percent" -> new io.keel.skill.PercentGrayPolicy(
                    skillRegistry, config.getPercent());
            case "allowlist" -> new io.keel.skill.SubjectAllowlistGrayPolicy(
                    skillRegistry, config.getTenants(), config.getSubjects());
            default -> throw new IllegalStateException(
                    "未知灰度策略 keel.skill-gray.strategy=" + config.getStrategy()
                            + "（可选：percent / allowlist）");
        };
    }

    @Bean
    public CitationGuard citationGuard() {
        return new CitationGuard();
    }
}
