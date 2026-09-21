package io.keel.core;

import java.io.Serializable;
import java.util.Optional;

/**
 * Skill 灰度策略 SPI（§8 第三期）：同名 skill 的灰度版本与稳定版本共存时，
 * 决定每个请求实际命中的版本。
 *
 * <p>放在 keel-core 与 {@link SkillRouter} 同位——keel-graph 只依赖 core，
 * 灰度选择在两条 skill 解析路径（显式指定 + 自动路由）的收口处统一生效。</p>
 *
 * <p>契约：</p>
 * <ul>
 *   <li>返回 {@code empty} 表示维持稳定版本（默认行为，不配置灰度即零变化）；</li>
 *   <li>实现必须是<strong>确定性</strong>的：同一 principal + skill 的多次请求
 *       应命中同一版本（sticky），不允许一会儿灰度一会稳定（灰度观测无意义）；</li>
 *   <li>实现不得抛异常——灰度选择失败按无灰度处理（fail-open 到稳定版，
 *       稳定版才是已验证的发布物）。</li>
 * </ul>
 */
public interface SkillGrayPolicy extends Serializable {

    /**
     * 为本次请求选择 skill 版本。
     *
     * @param request 当前请求（携带 principal，用于租户/用户维度灰度）
     * @param stable  已解析到的稳定版本定义
     * @return 灰度版本定义；empty 表示沿用稳定版本
     */
    Optional<SkillDefinition> select(AgentRequest request, SkillDefinition stable);
}
