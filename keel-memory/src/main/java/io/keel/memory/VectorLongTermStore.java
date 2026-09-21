package io.keel.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import io.keel.core.MemoryFragment;
import io.keel.core.MemoryLayer;

/**
 * 基于 LangChain4j 标准 {@link EmbeddingStore} / {@link EmbeddingModel} 的
 * L3 长期记忆向量后端（FR-52）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>不自研向量库：语义与相似度全部交给 LangChain4j 标准向量库实现
 *       （in-memory / pgvector / milvus 由构造方注入，本类只依赖标准接口）；</li>
 *   <li>隔离：向量 metadata 携带 tenant_id + subject_id + mem_key（FR-53），
 *       检索/删除全部按 metadata 过滤，禁止跨租户召回；</li>
 *   <li>幂等覆盖：同一 key 重新 upsert 时先按 metadata 删除旧向量再写入，
 *       保证一个 key 只有一条生效记忆；</li>
 *   <li>可撤销：{@link #revoke} 按 metadata 精确删除向量；</li>
 *   <li>时间序召回：{@link #search} 用轻量元数据索引（key → fragment）按时间倒序列举，
 *       语义召回 {@link #semanticSearch} 才走向量检索；
 *       后端必须实现 {@link EmbeddingStore#removeAll(Filter)}（0.36.x 内置实现均支持）。</li>
 * </ul>
 */
public final class VectorLongTermStore implements LongTermStore {

    private static final String META_TENANT = "tenant_id";
    private static final String META_SUBJECT = "subject_id";
    private static final String META_KEY = "mem_key";
    private static final String META_CREATED_AT = "created_at";

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    /**
     * 轻量元数据索引：仅存 key → fragment（不含向量），用于时间序列举与幂等检查。
     * 语义与相似度仍由 embeddingStore 负责，本索引不是向量库。
     */
    private final Map<TenantSubject, Map<String, MemoryFragment>> metadataIndex =
            new ConcurrentHashMap<>();

    public VectorLongTermStore(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.embeddingStore = Objects.requireNonNull(embeddingStore, "embeddingStore");
    }

    /** 便捷构造：默认使用进程内向量库（dev/test 用），生产建议注入持久化 EmbeddingStore。 */
    public VectorLongTermStore(EmbeddingModel embeddingModel) {
        this(embeddingModel, new InMemoryEmbeddingStore<>());
    }

    @Override
    public void upsert(MemoryFragment fragment) {
        Objects.requireNonNull(fragment, "fragment");
        if (fragment.getLayer() != MemoryLayer.L3) {
            throw new IllegalArgumentException("L3 长期存储只接受 L3 片段: " + fragment.getLayer());
        }
        if (fragment.getKey() == null || fragment.getKey().isBlank()) {
            throw new IllegalArgumentException("L3 长期记忆必须携带非空 key（用于幂等与撤销）");
        }
        String tenant = fragment.getTenantId();
        String subject = fragment.getSubjectId();
        // FR-52 幂等覆盖：同一 key 先删旧向量，保证一个 key 只有一条生效记忆
        embeddingStore.removeAll(scopeFilter(tenant, subject, fragment.getKey()));

        Metadata metadata = new Metadata()
                .put(META_TENANT, tenant)
                .put(META_SUBJECT, subject)
                .put(META_KEY, fragment.getKey())
                .put(META_CREATED_AT, fragment.getCreatedAt().toEpochMilli());
        TextSegment segment = TextSegment.from(fragment.getContent(), metadata);
        Response<dev.langchain4j.data.embedding.Embedding> embedding =
                embeddingModel.embed(fragment.getContent());
        embeddingStore.add(embedding.content(), segment);

        metadataIndex
                .computeIfAbsent(new TenantSubject(tenant, subject), key -> new ConcurrentHashMap<>())
                .put(fragment.getKey(), fragment);
    }

    @Override
    public void revoke(String tenantId, String subjectId, String key) {
        embeddingStore.removeAll(scopeFilter(tenantId, subjectId, key));
        Map<String, MemoryFragment> scope =
                metadataIndex.get(new TenantSubject(tenantId, subjectId));
        if (scope != null) {
            scope.remove(key);
        }
    }

    /** 语义召回（FR-52）：embedding 相似度排序，metadata 过滤租户隔离。 */
    @Override
    public List<MemoryFragment> semanticSearch(
            String tenantId, String subjectId, String query, int maxItems) {
        if (query == null || query.isBlank() || maxItems <= 0) {
            return List.of();
        }
        Response<dev.langchain4j.data.embedding.Embedding> queryEmbedding =
                embeddingModel.embed(query);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding.content())
                .maxResults(maxItems)
                .filter(scopeFilter(tenantId, subjectId))
                .build();
        List<MemoryFragment> result = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : embeddingStore.search(request).matches()) {
            result.add(toFragment(match.embedded()));
        }
        return result;
    }

    @Override
    public List<MemoryFragment> search(String tenantId, String subjectId, int maxItems) {
        Map<String, MemoryFragment> scope =
                metadataIndex.get(new TenantSubject(tenantId, subjectId));
        if (scope == null || scope.isEmpty()) {
            return List.of();
        }
        return scope.values().stream()
                .sorted(Comparator.comparing(MemoryFragment::getCreatedAt).reversed())
                .limit(Math.max(0, maxItems))
                .toList();
    }

    /**
     * FR-60：向量后端的 L3 记忆默认租户级共享（resourceId="*"），
     * 与 {@link InMemoryLongTermStore} 语义一致；具体 resourceId 返回空。
     */
    @Override
    public List<MemoryFragment> search(
            String tenantId, String subjectId, String resourceId, int maxItems) {
        if ("*".equals(resourceId)) {
            return search(tenantId, subjectId, maxItems);
        }
        return List.of();
    }

    /** 租户 + 主体隔离过滤（FR-53）。 */
    private static Filter scopeFilter(String tenantId, String subjectId) {
        return Filter.and(
                MetadataFilterBuilder.metadataKey(META_TENANT).isEqualTo(tenantId),
                MetadataFilterBuilder.metadataKey(META_SUBJECT).isEqualTo(subjectId));
    }

    private static Filter scopeFilter(String tenantId, String subjectId, String key) {
        return Filter.and(
                scopeFilter(tenantId, subjectId),
                MetadataFilterBuilder.metadataKey(META_KEY).isEqualTo(key));
    }

    private static MemoryFragment toFragment(TextSegment segment) {
        Metadata metadata = segment.metadata();
        return new MemoryFragment(
                MemoryLayer.L3,
                metadata.getString(META_TENANT),
                metadata.getString(META_SUBJECT),
                metadata.getString(META_KEY),
                segment.text(),
                java.time.Instant.ofEpochMilli(metadata.getLong(META_CREATED_AT)));
    }
}
