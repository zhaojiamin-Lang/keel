package io.keel.starter;

/**
 * NFR-04 启动自检探针（keel.startup.connectivity-check=warn/strict 时启用）。
 *
 * <p>每个子系统一个探针：模型 / 向量库 / Redis / Langfuse。探针在容器所有单例
 * 实例化完成后（{@code SmartInitializingSingleton}）执行一次；返回 {@code null}
 * 视为通过，返回非 {@code null} 视为失败——失败描述必须点名具体 bean 与相关
 * 配置键，让业务一眼知道修哪里（NFR-04「连不通要报哪个 bean」）。</p>
 *
 * <p>实现约定：{@link #check()} 必须自行捕获所有异常并转成失败描述，绝不向上
 * 抛出——warn 模式依赖这一点保证不阻断启动。业务可注册自己的 {@code StartupProbe}
 * bean 纳入自检（NFR-05 可扩展），失败项与内置探针一并汇总报告。</p>
 */
public interface StartupProbe {

    /**
     * 执行探测。返回 {@code null} = 通过；非 {@code null} = 失败描述
     * （必须点名 bean 与配置键）。实现内部不得抛出任何异常。
     */
    String check();
}
