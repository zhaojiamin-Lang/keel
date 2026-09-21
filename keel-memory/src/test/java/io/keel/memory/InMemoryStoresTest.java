package io.keel.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.keel.core.MemoryFragment;
import io.keel.core.MemoryLayer;

/**
 * 内存 L2 / L3 store 的隔离、顺序、幂等与撤销测试（FR-51/FR-52/FR-53）。
 */
class InMemoryStoresTest {

    private InMemoryEpisodicStore episodic;
    private InMemoryLongTermStore longTerm;

    @BeforeEach
    void setUp() {
        episodic = new InMemoryEpisodicStore();
        longTerm = new InMemoryLongTermStore();
    }

    @Test
    void episodicRecentReturnsNewestFirst() {
        episodic.append(fragment(MemoryLayer.L2, "t1", "u1", null, "第一条"));
        episodic.append(fragment(MemoryLayer.L2, "t1", "u1", null, "第二条"));
        episodic.append(fragment(MemoryLayer.L2, "t1", "u1", null, "第三条"));

        List<MemoryFragment> recent = episodic.recent("t1", "u1", 2);

        assertThat(recent).extracting(MemoryFragment::getContent)
                .containsExactly("第三条", "第二条");
    }

    @Test
    void episodicIsolatedAcrossSubjects() {
        episodic.append(fragment(MemoryLayer.L2, "t1", "u1", null, "u1 的轨迹"));

        assertThat(episodic.recent("t1", "u2", 5)).isEmpty();
    }

    @Test
    void longTermUpsertIsIdempotentByKey() {
        longTerm.upsert(fragment(MemoryLayer.L3, "t1", "u1", "lang", "偏好中文"));
        longTerm.upsert(fragment(MemoryLayer.L3, "t1", "u1", "lang", "偏好英文"));

        List<MemoryFragment> search = longTerm.search("t1", "u1", 5);

        assertThat(search).hasSize(1);
        assertThat(search.get(0).getContent()).isEqualTo("偏好英文");
    }

    @Test
    void longTermRevokeRemovesOnlyTargetKey() {
        longTerm.upsert(fragment(MemoryLayer.L3, "t1", "u1", "lang", "偏好中文"));
        longTerm.upsert(fragment(MemoryLayer.L3, "t1", "u1", "tone", "正式语气"));

        longTerm.revoke("t1", "u1", "lang");

        List<MemoryFragment> search = longTerm.search("t1", "u1", 5);
        assertThat(search).hasSize(1);
        assertThat(search.get(0).getKey()).isEqualTo("tone");
    }

    @Test
    void longTermIsolatedAcrossTenants() {
        longTerm.upsert(fragment(MemoryLayer.L3, "t1", "u1", "lang", "t1 的偏好"));

        assertThat(longTerm.search("t2", "u1", 5)).isEmpty();
    }

    @Test
    void longTermRequiresNonBlankKey() {
        assertThatThrownBy(() -> longTerm.upsert(
                fragment(MemoryLayer.L3, "t1", "u1", null, "无 key")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    private static MemoryFragment fragment(MemoryLayer layer, String tenant, String subject,
            String key, String content) {
        return new MemoryFragment(layer, tenant, subject, key, content, Instant.now());
    }
}
