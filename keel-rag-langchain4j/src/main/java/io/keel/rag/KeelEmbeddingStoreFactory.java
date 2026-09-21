package io.keel.rag;

import java.util.Objects;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

/**
 * 按 {@link KeelEmbeddingStoreConfig} 构造生产级 {@link EmbeddingStore}（FR-40）。
 *
 * <ul>
 *   <li>只做构造与 fail-fast 校验，真正的连接在首次读写时建立；</li>
 *   <li>pgvector / milvus 的类属于 optional 依赖：若业务选择了某后端却未引入对应
 *       langchain4j 模块，构造时抛 {@link NoClassDefFoundError}，这里转成带明确
 *       修复建议的 {@link IllegalStateException}（FR-03）；</li>
 *   <li>缺少必填连接信息（dimension / 数据库账号等）在连接前就抛错，避免半初始化。</li>
 * </ul>
 */
public final class KeelEmbeddingStoreFactory {

    private KeelEmbeddingStoreFactory() {
    }

    public static EmbeddingStore<TextSegment> create(KeelEmbeddingStoreConfig config) {
        Objects.requireNonNull(config, "config");
        String type = config.getType();
        return switch (type) {
            case KeelEmbeddingStoreConfig.TYPE_IN_MEMORY -> new InMemoryEmbeddingStore<>();
            case KeelEmbeddingStoreConfig.TYPE_PGVECTOR -> buildPgVector(config);
            case KeelEmbeddingStoreConfig.TYPE_MILVUS -> buildMilvus(config);
            default -> throw new IllegalArgumentException(
                    "未知的向量库类型: " + type + "（支持 in-memory / pgvector / milvus）");
        };
    }

    private static EmbeddingStore<TextSegment> buildPgVector(KeelEmbeddingStoreConfig config) {
        requireDimension(config, "pgvector");
        if (!hasText(config.getDatabase())) {
            throw new IllegalArgumentException("pgvector 必须配置 database");
        }
        if (!hasText(config.getUser())) {
            throw new IllegalArgumentException("pgvector 必须配置 user");
        }
        if (!hasText(config.getPassword())) {
            throw new IllegalArgumentException("pgvector 必须配置 password");
        }
        try {
            return PgVectorEmbeddingStore.builder()
                    .host(config.getHost())
                    .port(config.getPort() != null ? config.getPort() : 5432)
                    .database(config.getDatabase())
                    .user(config.getUser())
                    .password(config.getPassword())
                    .table(config.getTable())
                    .dimension(config.getDimension())
                    .build();
        } catch (NoClassDefFoundError error) {
            throw new IllegalStateException(
                    "选择 pgvector 但 classpath 缺少 langchain4j-pgvector，请补充该依赖", error);
        }
    }

    private static EmbeddingStore<TextSegment> buildMilvus(KeelEmbeddingStoreConfig config) {
        requireDimension(config, "milvus");
        try {
            MilvusEmbeddingStore.Builder builder = MilvusEmbeddingStore.builder()
                    .host(config.getHost())
                    .port(config.getPort() != null ? config.getPort() : 19530)
                    .collectionName(config.getCollectionName())
                    .dimension(config.getDimension());
            if (hasText(config.getUri())) {
                builder.uri(config.getUri());
            }
            if (hasText(config.getToken())) {
                builder.token(config.getToken());
            }
            return builder.build();
        } catch (NoClassDefFoundError error) {
            throw new IllegalStateException(
                    "选择 milvus 但 classpath 缺少 langchain4j-milvus，请补充该依赖", error);
        }
    }

    private static void requireDimension(KeelEmbeddingStoreConfig config, String type) {
        if (config.getDimension() == null || config.getDimension() <= 0) {
            throw new IllegalArgumentException(
                    type + " 必须配置正的 dimension（与 embedding 模型输出维度一致）");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
