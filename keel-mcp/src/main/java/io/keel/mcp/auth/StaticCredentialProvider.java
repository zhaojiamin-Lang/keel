package io.keel.mcp.auth;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.keel.core.KeelPrincipal;

/**
 * FR-31：静态凭证提供者——从配置的 serverName→token 映射查凭证。
 *
 * <p>用于多 server 场景，每个 server 有独立的 Bearer token。
 * principal 不参与解析（所有 principal 共享同一 token 映射）；
 * 真正按 principal 换凭证需业务自定义 {@link CredentialProvider}。</p>
 *
 * <p>fail-closed 语义：若 serverName 在映射中存在凭证，但 principal 无权访问
 * （通过 {@link #requirePrincipalMatch} 配置），则返回 empty 触发拒绝。
 * 默认不要求 principal 匹配（向后兼容）。</p>
 */
public final class StaticCredentialProvider implements CredentialProvider {

    private final Map<String, String> serverTokens;
    private final boolean requirePrincipalMatch;

    public StaticCredentialProvider(Map<String, String> serverTokens) {
        this(serverTokens, false);
    }

    public StaticCredentialProvider(Map<String, String> serverTokens, boolean requirePrincipalMatch) {
        this.serverTokens = Map.copyOf(Objects.requireNonNull(serverTokens, "serverTokens"));
        this.requirePrincipalMatch = requirePrincipalMatch;
    }

    @Override
    public Optional<String> resolveCredential(KeelPrincipal principal, String serverName) {
        Objects.requireNonNull(principal, "principal");
        if (serverName == null) {
            return Optional.empty();
        }
        String token = serverTokens.get(serverName);
        if (token == null) {
            return Optional.empty();
        }
        // FR-31：requirePrincipalMatch=true 时，principal 的 resourceIds 必须包含 serverName，
        // 否则返回 empty（触发 gateway fail-closed 拒绝）
        if (requirePrincipalMatch) {
            if (principal.getResourceIds() == null
                    || !principal.getResourceIds().contains(serverName)) {
                return Optional.empty();
            }
        }
        return Optional.of(token);
    }

    @Override
    public boolean isCredentialRequired(String serverName) {
        return serverName != null && serverTokens.containsKey(serverName);
    }
}