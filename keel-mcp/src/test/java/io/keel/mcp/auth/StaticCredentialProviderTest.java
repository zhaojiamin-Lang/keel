package io.keel.mcp.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.keel.core.KeelPrincipal;

class StaticCredentialProviderTest {

    private final KeelPrincipal principal = new KeelPrincipal("tenant", "subject", List.of());

    @Test
    void resolvesTokenForConfiguredServer() {
        StaticCredentialProvider provider = new StaticCredentialProvider(
                Map.of("gitlab", "gitlab-token", "oa", "oa-token"));

        Optional<String> credential = provider.resolveCredential(principal, "gitlab");

        assertThat(credential).contains("gitlab-token");
    }

    @Test
    void returnsEmptyForUnconfiguredServer() {
        StaticCredentialProvider provider = new StaticCredentialProvider(
                Map.of("gitlab", "gitlab-token"));

        Optional<String> credential = provider.resolveCredential(principal, "unknown");

        assertThat(credential).isEmpty();
    }

    @Test
    void returnsEmptyForNullServerName() {
        StaticCredentialProvider provider = new StaticCredentialProvider(
                Map.of("gitlab", "gitlab-token"));

        Optional<String> credential = provider.resolveCredential(principal, null);

        assertThat(credential).isEmpty();
    }

    @Test
    void allowAllProviderAlwaysReturnsEmpty() {
        Optional<String> credential = CredentialProvider.ALLOW_ALL
                .resolveCredential(principal, "any");

        assertThat(credential).isEmpty();
    }

    @Test
    void requirePrincipalMatchAllowsPrincipalWithResource() {
        // FR-31：requirePrincipalMatch=true 时，resourceIds 含 server 的 principal 放行
        StaticCredentialProvider provider = new StaticCredentialProvider(
                Map.of("gitlab", "gitlab-token"), true);
        KeelPrincipal allowed = new KeelPrincipal("tenant", "subject", List.of("gitlab"));

        Optional<String> credential = provider.resolveCredential(allowed, "gitlab");

        assertThat(credential).contains("gitlab-token");
        assertThat(provider.isCredentialRequired("gitlab")).isTrue();
    }

    @Test
    void requirePrincipalMatchRejectsPrincipalWithoutResource() {
        // FR-31 fail-closed：resourceIds 不含 server → empty，由 gateway 层拒绝
        StaticCredentialProvider provider = new StaticCredentialProvider(
                Map.of("gitlab", "gitlab-token"), true);

        Optional<String> credential = provider.resolveCredential(principal, "gitlab");

        assertThat(credential).isEmpty();
    }

    @Test
    void defaultConstructorDoesNotRequirePrincipalMatch() {
        // 向后兼容：默认不校验 principal，任意 principal 都能解析到配置的 token
        StaticCredentialProvider provider = new StaticCredentialProvider(
                Map.of("gitlab", "gitlab-token"));

        Optional<String> credential = provider.resolveCredential(principal, "gitlab");

        assertThat(credential).contains("gitlab-token");
    }
}