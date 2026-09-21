package io.keel.starter;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * NFR-04 启动自检编排：容器所有单例装配完成后逐项执行 {@link StartupProbe}，
 * 失败项<b>一次性</b>汇总报告（点名 bean 与配置键，不挤牙膏）。
 *
 * <p>模式（keel.startup.connectivity-check，默认 off = 现行为零变化）：
 * <ul>
 *   <li>off —— 不执行任何探测，可选依赖保持静默降级（设计取舍：可选依赖不强制阻塞启动）；</li>
 *   <li>warn —— 失败项 log.warn，不阻断启动；</li>
 *   <li>strict —— 抛出 IllegalStateException，启动失败。</li>
 * </ul></p>
 *
 * <p>未知模式在构造期即拒绝（fail-closed，与灰度策略未知 strategy 的处理一致），
 * 避免业务拼写错误后自检静默失效。</p>
 */
public class KeelStartupSelfCheck implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(KeelStartupSelfCheck.class);

    private final KeelProperties properties;
    private final List<StartupProbe> probes;

    public KeelStartupSelfCheck(KeelProperties properties, List<StartupProbe> probes) {
        String mode = properties.getStartup().getConnectivityCheck();
        if (!"off".equalsIgnoreCase(mode) && !"warn".equalsIgnoreCase(mode)
                && !"strict".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException(
                    "未知 keel.startup.connectivity-check=" + mode + "（可选：off / warn / strict）");
        }
        this.properties = properties;
        this.probes = List.copyOf(probes);
    }

    @Override
    public void afterSingletonsInstantiated() {
        String mode = properties.getStartup().getConnectivityCheck();
        if ("off".equalsIgnoreCase(mode)) {
            return;
        }
        List<String> failures = new ArrayList<>();
        for (StartupProbe probe : probes) {
            String failure = probe.check();  // 探针约定永不抛出
            if (failure != null) {
                failures.add(failure);
            }
        }
        if (failures.isEmpty()) {
            return;
        }
        StringBuilder report = new StringBuilder("Keel 启动自检（NFR-04）发现 ")
                .append(failures.size()).append(" 个子系统异常：");
        for (int i = 0; i < failures.size(); i++) {
            report.append("\n  ").append(i + 1).append(". ").append(failures.get(i));
        }
        if ("strict".equalsIgnoreCase(mode)) {
            throw new IllegalStateException(report.toString());
        }
        log.warn(report.toString());
    }
}
