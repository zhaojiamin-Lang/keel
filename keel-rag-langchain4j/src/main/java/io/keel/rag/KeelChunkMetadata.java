package io.keel.rag;

/**
 * Keel 写入向量库的每个 TextSegment 必须携带的元数据键。
 *
 * <p>chunk_id/source 用于产出可核验的 {@link io.keel.core.Citation}（FR-39/FR-42）；
 * tenant_id/resource_id 是代码级隔离条件（G4/FR-41），不允许只靠 Prompt 约束。</p>
 */
public final class KeelChunkMetadata {

    /** 知识块全局唯一 ID；缺失该键的召回片段会被直接丢弃，不进入引用列表。 */
    public static final String CHUNK_ID = "chunk_id";

    /** 来源标识（文档名 / URL / 工单号等），用于答案引用展示；缺失时降级为空串。 */
    public static final String SOURCE = "source";

    /** 租户 ID，检索时强制等值过滤。 */
    public static final String TENANT_ID = "tenant_id";

    /** 资源（项目 / 空间等）ID，检索时强制在 principal.resourceIds 集合内。 */
    public static final String RESOURCE_ID = "resource_id";

    /** 知识文档版本（FR-44）；写入时携带，未对齐版本不得作为关单/变更类依据。 */
    public static final String DOC_VERSION = "doc_version";

    private KeelChunkMetadata() {
    }
}
