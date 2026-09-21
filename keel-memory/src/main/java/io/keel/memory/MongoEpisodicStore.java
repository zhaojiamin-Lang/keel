package io.keel.memory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import io.keel.core.MemoryFragment;
import io.keel.core.MemoryLayer;

/**
 * 基于 MongoDB 的 L2 情景记忆存储（FR-51）。
 *
 * <p>按 tenant_id + subject_id 隔离（FR-53），按 created_at 倒序取最近。半结构化轨迹
 * （Agent 轨迹、工具 IO）正是 Mongo 的适用场景（需求 7.1）。连接是惰性的：构造
 * {@link MongoClient} 不建立网络连接，首次读写才连。</p>
 */
public final class MongoEpisodicStore implements EpisodicStore {

    private static final String COL_TENANT = "tenant_id";
    private static final String COL_SUBJECT = "subject_id";
    private static final String COL_LAYER = "layer";
    private static final String COL_CONTENT = "content";
    private static final String COL_CREATED_AT = "created_at";

    private final MongoCollection<Document> collection;

    public MongoEpisodicStore(String connectionString, String database, String collection) {
        this(resolveCollection(connectionString, database, collection));
    }

    /** 供测试与自备 {@link MongoCollection} 的业务注入（如 Testcontainers 共享连接）。 */
    MongoEpisodicStore(MongoCollection<Document> collection) {
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    private static MongoCollection<Document> resolveCollection(
            String connectionString, String database, String collection) {
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalArgumentException("Mongo L2 情景存储必须配置 connectionString");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("Mongo L2 情景存储必须配置 database");
        }
        String coll = collection == null || collection.isBlank() ? "keel_episodic_memory" : collection;
        return MongoClients.create(connectionString)
                .getDatabase(database)
                .getCollection(coll);
    }

    @Override
    public void append(MemoryFragment fragment) {
        Objects.requireNonNull(fragment, "fragment");
        if (fragment.getLayer() != MemoryLayer.L2) {
            throw new IllegalArgumentException("L2 情景存储只接受 L2 片段: " + fragment.getLayer());
        }
        collection.insertOne(new Document()
                .append(COL_TENANT, fragment.getTenantId())
                .append(COL_SUBJECT, fragment.getSubjectId())
                .append(COL_LAYER, "L2")
                .append(COL_CONTENT, fragment.getContent())
                .append(COL_CREATED_AT, Date.from(fragment.getCreatedAt())));
    }

    @Override
    public List<MemoryFragment> recent(String tenantId, String subjectId, int maxItems) {
        Bson filter = Filters.and(
                Filters.eq(COL_TENANT, tenantId),
                Filters.eq(COL_SUBJECT, subjectId));
        List<MemoryFragment> result = new ArrayList<>();
        for (Document doc : collection.find(filter)
                .sort(Sorts.descending(COL_CREATED_AT))
                .limit(Math.max(0, maxItems))) {
            result.add(new MemoryFragment(
                    MemoryLayer.L2,
                    tenantId,
                    subjectId,
                    null,
                    doc.getString(COL_CONTENT),
                    doc.getDate(COL_CREATED_AT).toInstant()));
        }
        return result;
    }
}
