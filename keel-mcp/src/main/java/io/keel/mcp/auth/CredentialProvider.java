package io.keel.mcp.auth;

import java.util.Optional;

import io.keel.core.KeelPrincipal;

/**
 * FR-31：per-call 凭证解析端口。按 principal + server 名解析 per-call 凭证，
 * 用于 gateway 层在调用前校验 principal 是否有权访问该 server。
 *
 * <p>SDK 0.10.0 的 CallToolRequest 不支持 per-request header，真正的 per-call
 * 注入需 SDK 升级（>= 0.11）。本端口做的是「凭证校验 + 准备」——gateway 层
 * 确认 principal 有权访问该 server 的凭证，无凭证则 fail-closed 拒绝
 * （不静默放行）。</p>
 *
 * <p>实现示例：</p>
 * <ul>
 *   <li>{@link StaticCredentialProvider}：从配置的 server→token 映射查凭证；</li>
 *   <li>业务自定义：按 principal.subjectId 查 OAuth token / JWT 等。</li>
 * </ul>
 */
public interface CredentialProvider {

    /**
     * 按 principal + server 名解析 per-call 凭证。
     *
     * @param principal 当前请求的主体（含 tenantId / subjectId / resourceIds）
     * @param serverName MCP server 名（单 server 场景为 "default"）
     * @return 凭证字符串（如 Bearer token 值），empty 表示该 server 无凭证要求
     *         （放行静态头行为）
     */
    Optional<String> resolveCredential(KeelPrincipal principal, String serverName);

    /** 无凭证要求的默认实现：永远返回 empty（放行静态头行为）；isCredentialRequired 沿用接口默认 false。 */
    CredentialProvider ALLOW_ALL = new CredentialProvider() {
        @Override
        public Optional<String> resolveCredential(KeelPrincipal principal, String serverName) {
            return Optional.empty();
        }
    };

    /**
     * 判断指定 server 是否有凭证要求。
     * <p>gateway 层据此决定 fail-closed：若返回 true 但 {@link #resolveCredential}
     * 返回 empty，则拒绝调用（不静默放行）。默认 false（向后兼容）。</p>
     *
     * @param serverName MCP server 名
     * @return true 表示该 server 要求凭证，空凭证触发拒绝
     */
    default boolean isCredentialRequired(String serverName) {
        return false;
    }
}