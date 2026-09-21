package io.keel.rag;

import java.util.Collection;

import dev.langchain4j.store.embedding.filter.Filter;
import io.keel.core.KeelPrincipal;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 把 {@link KeelPrincipal} 的身份边界翻译成向量库元数据 Filter（G4/FR-41/FR-60）。
 *
 * <p>强制条件：{@code tenant_id = principal.tenantId AND resource_id IN principal.resourceIds}。
 * 这是代码化隔离，模型提示词、查询文本都无法放宽该范围。</p>
 */
public final class AclScopeFilterFactory {

    private AclScopeFilterFactory() {
    }

    /**
     * 构造 ACL 过滤条件。
     *
     * @param principal 当前主体，resourceIds 必须非空（空集合的短路由调用方处理，
     *                  避免生成 {@code IN ()} 之类语义不确定的条件）
     */
    public static Filter create(KeelPrincipal principal) {
        Collection<String> resourceIds = principal.getResourceIds();
        if (resourceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "resourceIds 为空时不应构造向量检索 Filter，应直接短路返回空召回");
        }
        return metadataKey(KeelChunkMetadata.TENANT_ID)
                .isEqualTo(principal.getTenantId())
                .and(metadataKey(KeelChunkMetadata.RESOURCE_ID).isIn(resourceIds));
    }
}
