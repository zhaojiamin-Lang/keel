package io.keel.starter;

import org.springframework.beans.factory.ObjectProvider;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * NFR-04 向量库探针：keel.retrieval.store.type 显式配置时，EmbeddingModel 与
 * EmbeddingStore bean 必须齐备（缺任一检索管道即不可用）。
 *
 * <p>边界说明：LangChain4j 0.36.x 的 EmbeddingStore 无统一 ping API——真实调用
 * 会产生 embedding 计费或强依赖具体后端协议（pgvector/milvus 各异），本探针只做
 * bean 齐备性校验；网络连通性由检索失败路径与后端自身监控负责。</p>
 */
final class VectorStoreStartupProbe implements StartupProbe {

    private final KeelProperties properties;
    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final ObjectProvider<EmbeddingStore<?>> embeddingStores;

    VectorStoreStartupProbe(KeelProperties properties,
            ObjectProvider<EmbeddingModel> embeddingModels,
            ObjectProvider<EmbeddingStore<?>> embeddingStores) {
        this.properties = properties;
        this.embeddingModels = embeddingModels;
        this.embeddingStores = embeddingStores;
    }

    @Override
    public String check() {
        String type = properties.getRetrieval().getStore().getType();
        if (type == null || type.isBlank()) {
            // type 未配置 = 走「业务自备 EmbeddingStore bean」路径，不在此探针职责内
            return null;
        }
        if (embeddingModels.getIfAvailable() == null) {
            return "向量: keel.retrieval.store.type=" + type + " 但容器中没有 EmbeddingModel bean，"
                    + "检索管道不可用；请注册 EmbeddingModel bean（RAG 与 L3 向量记忆共用）";
        }
        if (embeddingStores.getIfAvailable() == null) {
            return "向量: keel.retrieval.store.type=" + type + " 但容器中没有 EmbeddingStore bean"
                    + "（bean 'keelEmbeddingStore' 创建失败或被覆盖），检索管道不可用；"
                    + "请检查 keel.retrieval.store.* 配置";
        }
        return null;
    }
}
