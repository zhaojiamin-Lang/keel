package io.keel.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import io.keel.core.MemoryFragment;
import io.keel.core.MemoryLayer;

/**
 * {@link VectorLongTermStore} 测试（FR-52）：语义排序 / 幂等覆盖 / 可撤销 /
 * 租户隔离（FR-53）/ 时间序退化 / 参数校验。
 *
 * <p>embedding 用确定性 bigram 哈希桩，向量库用 LangChain4j 内置
 * {@code InMemoryEmbeddingStore}——不依赖任何外部服务，但走的是与生产一致的
 * 标准 EmbeddingStore API 路径。</p>
 */
class VectorLongTermStoreTest {

    private VectorLongTermStore store;

    @BeforeEach
    void setUp() {
        store = new VectorLongTermStore(new BigramHashEmbeddingModel());
    }

    @Test
    void semanticSearchRanksByQuerySimilarity() {
        store.upsert(fragment("t1", "u1", "pref-lang", "偏好中文回答",
                Instant.parse("2026-01-01T00:00:00Z")));
        store.upsert(fragment("t1", "u1", "pref-code", "喜欢用 Java 写代码",
                Instant.parse("2026-01-02T00:00:00Z")));

        List<MemoryFragment> hits = store.semanticSearch(
                "t1", "u1", "中文回答偏好", 5);

        assertThat(hits).isNotEmpty();
        // 语义最相关的排最前（与时间序相反：pref-code 更新）
        assertThat(hits.get(0).getKey()).isEqualTo("pref-lang");
    }

    @Test
    void upsertOverwritesSameKeyIdempotently() {
        store.upsert(fragment("t1", "u1", "pref-lang", "偏好中文回答",
                Instant.parse("2026-01-01T00:00:00Z")));
        store.upsert(fragment("t1", "u1", "pref-lang", "偏好英文回答，中文次要",
                Instant.parse("2026-01-03T00:00:00Z")));

        // key 幂等：元数据索引只留最新一条
        List<MemoryFragment> timeOrdered = store.search("t1", "u1", 10);
        assertThat(timeOrdered).hasSize(1);
        assertThat(timeOrdered.get(0).getContent()).isEqualTo("偏好英文回答，中文次要");

        // 语义召回不会同时命中新旧两条（旧向量已按 key 删除）
        List<MemoryFragment> hits = store.semanticSearch("t1", "u1", "回答偏好", 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getContent()).isEqualTo("偏好英文回答，中文次要");
    }

    @Test
    void revokeRemovesMemoryPermanently() {
        store.upsert(fragment("t1", "u1", "pref-lang", "偏好中文回答",
                Instant.parse("2026-01-01T00:00:00Z")));

        store.revoke("t1", "u1", "pref-lang");

        assertThat(store.search("t1", "u1", 10)).isEmpty();
        assertThat(store.semanticSearch("t1", "u1", "偏好回答", 10)).isEmpty();
    }

    @Test
    void semanticSearchIsIsolatedByTenantAndSubject() {
        store.upsert(fragment("t1", "u1", "pref-lang", "偏好中文回答",
                Instant.parse("2026-01-01T00:00:00Z")));

        assertThat(store.semanticSearch("other-tenant", "u1", "偏好中文回答", 10)).isEmpty();
        assertThat(store.semanticSearch("t1", "other-subject", "偏好中文回答", 10)).isEmpty();
        assertThat(store.search("other-tenant", "u1", 10)).isEmpty();
    }

    @Test
    void searchWithoutQueryFallsBackToTimeOrder() {
        store.upsert(fragment("t1", "u1", "older", "旧偏好",
                Instant.parse("2026-01-01T00:00:00Z")));
        store.upsert(fragment("t1", "u1", "newer", "新偏好",
                Instant.parse("2026-01-02T00:00:00Z")));

        List<MemoryFragment> hits = store.search("t1", "u1", 10);

        // 时间序退化路径（TieredMemoryPort 对非向量后端的兼容语义）：最新的在前
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).getKey()).isEqualTo("newer");
        assertThat(hits.get(1).getKey()).isEqualTo("older");
    }

    @Test
    void rejectsNonL3FragmentAndBlankKey() {
        assertThatThrownBy(() -> store.upsert(new MemoryFragment(
                MemoryLayer.L2, "t1", "u1", "k", "内容", Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.upsert(new MemoryFragment(
                MemoryLayer.L3, "t1", "u1", " ", "内容", Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MemoryFragment fragment(String tenant, String subject,
            String key, String content, Instant createdAt) {
        return new MemoryFragment(MemoryLayer.L3, tenant, subject, key, content, createdAt);
    }

    /**
     * 确定性 embedding 桩：字符 bigram 哈希到 64 维。
     * 只用于测试语义排序路径，不追求语义质量。
     */
    static class BigramHashEmbeddingModel implements EmbeddingModel {

        private static final int DIM = 64;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream()
                    .map(segment -> embed(segment.text()).content())
                    .toList());
        }

        @Override
        public Response<Embedding> embed(String text) {
            float[] vector = new float[DIM];
            for (int i = 0; i + 1 < text.length(); i++) {
                int index = Math.abs(text.substring(i, i + 2).hashCode()) % DIM;
                vector[index] += 1f;
            }
            return Response.from(Embedding.from(vector));
        }
    }
}
