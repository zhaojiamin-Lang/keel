package io.keel.client;

import io.keel.core.KeelPrincipal;

/**
 * 当前请求的身份解析 SPI（FR-60：权限把租户 + 资源级身份贯穿 RAG / Memory / MCP）。
 *
 * <p><b>为什么需要它：</b>{@link io.keel.core.KeelPrincipal} 是请求级概念——同一进程会
 * 服务多个租户，身份必须「每次请求解析」而不是「启动时固定」。业务注册一个本接口的
 * bean 即被 starter 自动采用；不注册时使用 {@link DefaultPrincipalResolver}
 * （读 {@code keel.principal.*} 配置），保证零配置项目也能启动。</p>
 *
 * <p><b>实现约定：</b></p>
 * <ul>
 *     <li>纯读取，不得产生副作用、不得发起远程调用（每次 Agent 请求都会调用）；</li>
 *     <li>解析不到身份时返回兜底身份而不是抛异常——身份来源（SecurityContext / 请求头 /
 *         RPC 上下文）在业务侧五花八门，抛异常会把「未登录」放大成系统故障；</li>
 *     <li>若业务要求「无身份必须拒绝」，请在实现里自行 fail-closed（例如返回一个
 *         resourceIds 为空的 principal，让下游 Guard 拦截）。</li>
 * </ul>
 *
 * <p>典型实现（Spring Security）：</p>
 * <pre>{@code
 * @Bean
 * PrincipalResolver principalResolver() {
 *     return () -> {
 *         Authentication auth = SecurityContextHolder.getContext().getAuthentication();
 *         // 从 auth 中取租户与资源权限
 *         return new KeelPrincipal(tenantId, auth.getName(), resourceIds);
 *     };
 * }
 * }</pre>
 */
@FunctionalInterface
public interface PrincipalResolver {

    /**
     * 解析当前请求的身份。
     *
     * @return 当前请求的 principal，不得返回 {@code null}
     */
    KeelPrincipal resolve();
}
