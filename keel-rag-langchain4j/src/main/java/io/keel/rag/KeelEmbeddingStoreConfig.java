package io.keel.rag;

import java.util.Objects;

/**
 * 生产向量库选择配置（FR-40）。纯数据对象，不依赖 Spring：
 * starter 把 {@code keel.retrieval.store.*} 绑定后翻译成本类，交给
 * {@link KeelEmbeddingStoreFactory} 构造具体的 {@code EmbeddingStore}。
 *
 * <p>三种后端：</p>
 * <ul>
 *   <li>{@code in-memory}：示例 / 测试环境，无外部依赖；</li>
 *   <li>{@code pgvector}：小项目 / 示例环境，少引入一个组件（PG 已在技术栈内）；</li>
 *   <li>{@code milvus}：生产级多租户向量检索。</li>
 * </ul>
 */
public final class KeelEmbeddingStoreConfig {

    public static final String TYPE_IN_MEMORY = "in-memory";
    public static final String TYPE_PGVECTOR = "pgvector";
    public static final String TYPE_MILVUS = "milvus";

    private final String type;
    private final Integer dimension;
    private final String host;
    private final Integer port;
    private final String database;
    private final String user;
    private final String password;
    private final String table;
    private final String collectionName;
    private final String uri;
    private final String token;

    private KeelEmbeddingStoreConfig(Builder builder) {
        this.type = builder.type;
        this.dimension = builder.dimension;
        this.host = builder.host;
        this.port = builder.port;
        this.database = builder.database;
        this.user = builder.user;
        this.password = builder.password;
        this.table = builder.table;
        this.collectionName = builder.collectionName;
        this.uri = builder.uri;
        this.token = builder.token;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getType() {
        return type;
    }

    public Integer getDimension() {
        return dimension;
    }

    public String getHost() {
        return host;
    }

    public Integer getPort() {
        return port;
    }

    public String getDatabase() {
        return database;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public String getTable() {
        return table;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public String getUri() {
        return uri;
    }

    public String getToken() {
        return token;
    }

    public static final class Builder {

        private String type;
        private Integer dimension;
        private String host = "localhost";
        private Integer port;
        private String database;
        private String user;
        private String password;
        private String table = "keel_embeddings";
        private String collectionName = "keel_embeddings";
        private String uri;
        private String token;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder dimension(Integer dimension) {
            this.dimension = dimension;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(Integer port) {
            this.port = port;
            return this;
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        public Builder user(String user) {
            this.user = user;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder table(String table) {
            this.table = table;
            return this;
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public Builder token(String token) {
            this.token = token;
            return this;
        }

        public KeelEmbeddingStoreConfig build() {
            Objects.requireNonNull(type, "type 未指定（支持 in-memory / pgvector / milvus）");
            return new KeelEmbeddingStoreConfig(this);
        }
    }
}
