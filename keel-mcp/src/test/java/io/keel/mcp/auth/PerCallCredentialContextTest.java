package io.keel.mcp.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PerCallCredentialContextTest {

    @AfterEach
    void cleanUp() {
        PerCallCredentialContext.clear();
    }

    @Test
    void setAndGetCredential() {
        PerCallCredentialContext.set("token-abc");

        assertThat(PerCallCredentialContext.hasCredential()).isTrue();
        assertThat(PerCallCredentialContext.get()).isEqualTo("token-abc");
    }

    @Test
    void clearRemovesCredential() {
        PerCallCredentialContext.set("token-abc");
        PerCallCredentialContext.clear();

        assertThat(PerCallCredentialContext.hasCredential()).isFalse();
        assertThat(PerCallCredentialContext.get()).isNull();
    }

    @Test
    void threadIsolation() throws Exception {
        PerCallCredentialContext.set("main-thread-token");

        Thread t = new Thread(() -> {
            // 子线程看不到主线程的凭证
            assertThat(PerCallCredentialContext.hasCredential()).isFalse();
            // 子线程设置自己的凭证
            PerCallCredentialContext.set("child-thread-token");
            assertThat(PerCallCredentialContext.get()).isEqualTo("child-thread-token");
        });
        t.start();
        t.join();

        // 主线程凭证不受子线程影响
        assertThat(PerCallCredentialContext.get()).isEqualTo("main-thread-token");
    }

    @Test
    void emptyByDefault() {
        assertThat(PerCallCredentialContext.hasCredential()).isFalse();
        assertThat(PerCallCredentialContext.get()).isNull();
    }
}