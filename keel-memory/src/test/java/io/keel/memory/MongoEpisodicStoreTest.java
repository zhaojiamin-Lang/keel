package io.keel.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

import io.keel.core.MemoryFragment;
import io.keel.core.MemoryLayer;

/**
 * L2 情景记忆 Mongo 实现（FR-51/FR-53）。
 * 使用 Mockito 桩掉 MongoCollection，不依赖真实 Mongo 服务：
 * 验证写入文档形状（tenant/subject 隔离字段）、层级约束、读取映射与倒序 limit。
 */
class MongoEpisodicStoreTest {

    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> collection = mock(MongoCollection.class);
    private MongoEpisodicStore store;

    @BeforeEach
    void setUp() {
        store = new MongoEpisodicStore(collection);
    }

    @Test
    void constructorValidatesRequiredConfig() {
        assertThatThrownBy(() -> new MongoEpisodicStore(null, "db", "coll"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectionString");
        assertThatThrownBy(() -> new MongoEpisodicStore(" ", "db", "coll"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectionString");
        assertThatThrownBy(() -> new MongoEpisodicStore("mongodb://localhost", " ", "coll"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database");
    }

    @Test
    void appendRejectsNonL2Fragment() {
        // FR-53：禁止跨层混写——L2 存储只接受 L2 片段
        MemoryFragment l3 = new MemoryFragment(
                MemoryLayer.L3, "t1", "u1", "pref:lang", "偏好中文", Instant.now());

        assertThatThrownBy(() -> store.append(l3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L2");
    }

    @Test
    void appendWritesTenantSubjectIsolatedDocument() {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        store.append(new MemoryFragment(
                MemoryLayer.L2, "tenant-a", "user-1", null, "会话轨迹摘要", now));

        var captor = org.mockito.ArgumentCaptor.forClass(Document.class);
        verify(collection).insertOne(captor.capture());
        Document doc = captor.getValue();
        assertThat(doc.getString("tenant_id")).isEqualTo("tenant-a");
        assertThat(doc.getString("subject_id")).isEqualTo("user-1");
        assertThat(doc.getString("layer")).isEqualTo("L2");
        assertThat(doc.getString("content")).isEqualTo("会话轨迹摘要");
        assertThat(doc.getDate("created_at")).isEqualTo(Date.from(now));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentMapsDocumentsToL2Fragments() {
        Document doc1 = new Document()
                .append("tenant_id", "t1").append("subject_id", "u1")
                .append("layer", "L2").append("content", "较早的轨迹")
                .append("created_at", Date.from(Instant.parse("2026-09-18T10:00:00Z")));
        Document doc2 = new Document()
                .append("tenant_id", "t1").append("subject_id", "u1")
                .append("layer", "L2").append("content", "最近的轨迹")
                .append("created_at", Date.from(Instant.parse("2026-09-18T11:00:00Z")));

        @SuppressWarnings("unchecked")
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(doc1, doc2);

        @SuppressWarnings("unchecked")
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any(Bson.class))).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(cursor);

        List<MemoryFragment> fragments = store.recent("t1", "u1", 5);

        assertThat(fragments).hasSize(2);
        assertThat(fragments.get(0).getLayer()).isEqualTo(MemoryLayer.L2);
        assertThat(fragments.get(0).getTenantId()).isEqualTo("t1");
        assertThat(fragments.get(0).getSubjectId()).isEqualTo("u1");
        assertThat(fragments.get(0).getContent()).isEqualTo("较早的轨迹");
        assertThat(fragments.get(1).getContent()).isEqualTo("最近的轨迹");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentQueriesByTenantAndSubjectFilter() {
        @SuppressWarnings("unchecked")
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenReturn(false);

        @SuppressWarnings("unchecked")
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any(Bson.class))).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(cursor);

        assertThat(store.recent("tenant-x", "user-x", 3)).isEmpty();

        // FR-53：过滤器必须携带 tenant + subject，绝不能全表扫
        var captor = org.mockito.ArgumentCaptor.forClass(Bson.class);
        verify(collection).find(captor.capture());
        String rendered = captor.getValue().toBsonDocument(
                Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry())
                .toJson();
        assertThat(rendered).contains("tenant_id").contains("subject_id");
    }
}
