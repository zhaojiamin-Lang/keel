package io.keel.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * 生产级知识入库（FR-40/FR-41/FR-44）：切分 → embed → 写入向量库，并强制写入
 * 隔离元数据（tenant_id / resource_id）与引用元数据（chunk_id / source / doc_version）。
 *
 * <p>本类不依赖 Spring；业务（或 starter 装配的 bean）注入 {@link EmbeddingModel} 与
 * {@link EmbeddingStore} 后直接调用 {@link #ingest}。租户 / 资源隔离在入库时写死，
 * 检索时由 {@link AclScopeFilterFactory} 代码化强制，二者共用同一套元数据键。</p>
 */
public final class KeelDocumentIngestor {

    public static final int DEFAULT_MAX_SEGMENT_CHARS = 500;
    public static final int DEFAULT_MAX_OVERLAP_CHARS = 50;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> store;
    private final DocumentSplitter splitter;

    public KeelDocumentIngestor(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> store) {
        this(embeddingModel, store, DEFAULT_MAX_SEGMENT_CHARS, DEFAULT_MAX_OVERLAP_CHARS);
    }

    public KeelDocumentIngestor(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> store,
            int maxSegmentChars,
            int maxOverlapChars) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.store = Objects.requireNonNull(store, "store");
        if (maxSegmentChars <= 0 || maxOverlapChars < 0) {
            throw new IllegalArgumentException("maxSegmentChars 必须为正，maxOverlapChars 不能为负");
        }
        this.splitter = DocumentSplitters.recursive(maxSegmentChars, maxOverlapChars);
    }

    /**
     * 把一段知识写入向量库。
     *
     * @param text       原始文本，入库前按 {@code maxSegmentChars} 递归切分
     * @param source     来源标识（文档名 / URL / 工单号），进入 Citation.source
     * @param tenantId   租户 ID（FR-53 隔离）
     * @param resourceId 资源（项目 / 空间）ID（FR-41 隔离）
     * @param docVersion 文档版本（FR-44），可为 null
     * @return 本次写入的所有 chunk_id（与 Citation.chunkId 一致，供审计 / 回滚）
     */
    public List<String> ingest(String text, String source, String tenantId,
            String resourceId, String docVersion) {
        Objects.requireNonNull(text, "text");
        if (!hasText(source)) {
            throw new IllegalArgumentException("source 不能为空");
        }
        if (!hasText(tenantId)) {
            throw new IllegalArgumentException("tenantId 不能为空（FR-53 隔离）");
        }
        if (!hasText(resourceId)) {
            throw new IllegalArgumentException("resourceId 不能为空（FR-41 隔离）");
        }

        List<TextSegment> segments = splitter.split(Document.from(text));
        List<String> chunkIds = new ArrayList<>();
        int index = 0;
        for (TextSegment segment : segments) {
            if (segment == null || !hasText(segment.text())) {
                continue;
            }
            String chunkId = source + "#" + index++;
            Metadata metadata = segment.metadata() == null
                    ? new Metadata()
                    : segment.metadata().copy();
            metadata.put(KeelChunkMetadata.CHUNK_ID, chunkId);
            metadata.put(KeelChunkMetadata.SOURCE, source);
            metadata.put(KeelChunkMetadata.TENANT_ID, tenantId);
            metadata.put(KeelChunkMetadata.RESOURCE_ID, resourceId);
            if (hasText(docVersion)) {
                metadata.put(KeelChunkMetadata.DOC_VERSION, docVersion);
            }

            TextSegment enriched = TextSegment.from(segment.text(), metadata);
            Embedding embedding = embeddingModel.embed(enriched).content();
            store.add(embedding, enriched);
            chunkIds.add(chunkId);
        }
        return chunkIds;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
