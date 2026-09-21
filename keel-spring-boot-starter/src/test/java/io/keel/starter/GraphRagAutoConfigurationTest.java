package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.KeelPrincipal;
import io.keel.graph.spi.RetrievalPort;
import io.keel.rag.KeelChunkMetadata;

/**
 * graph + RAG 端到端装配：业务提供 EmbeddingModel / EmbeddingStore 后，
 * starter 自动装配 RetrievalPort；模型 RETRIEVE → 命中授权片段 → ANSWER 成功并带 citation。
 */
@SpringBootTest(properties = "keel.agent.mode=graph")
class GraphRagAutoConfigurationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private GraphRagTestFixtures.QueuedChatModel chatModel;

    @Autowired
    private RetrievalPort retrievalPort;

    @BeforeEach
    void resetChat() {
        chatModel.reset(
                "{\"action\":\"RETRIEVE\",\"query\":\"退款政策\"}",
                "{\"action\":\"ANSWER\",\"answer\":\"根据知识库，支持 7 天无理由退款\"}",
                "{\"verdict\":\"APPROVED\"}");
    }

    @Test
    void retrieveThenAnswerSucceedsWithCitations() {
        assertThat(retrievalPort).isNotNull();

        AgentResult result = keelAgent.run(request("wiki-qa", "退款多久到账"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("7 天无理由");
        assertThat(result.getCitations()).isNotEmpty();
        assertThat(result.getCitations().get(0).getChunkId()).isEqualTo("c-refund");
        assertThat(result.getCitations().get(0).getSource()).isEqualTo("refund-wiki");
    }

    private AgentRequest request(String skill, String input) {
        return AgentRequest.builder()
                // resourceIds 与种子片段的 resource_id 对齐，模拟已授权资源
                .principal(new KeelPrincipal("tenant-1", "subject", List.of("doc-1")))
                .skill(skill)
                .input(input)
                .build();
    }

    @SpringBootApplication
    static class RagTestApplication {

        @Bean
        ChatModel chatModel() {
            return new GraphRagTestFixtures.QueuedChatModel();
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return new GraphRagTestFixtures.HashingEmbeddingModel();
        }

        /** 内存向量库 + 一条带齐 chunk_id/source/tenant/resource 元数据的种子片段。 */
        @Bean
        EmbeddingStore<TextSegment> embeddingStore(EmbeddingModel embeddingModel) {
            InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
            TextSegment segment = TextSegment.from(
                    "退款政策：支持 7 天无理由退款，3 个工作日内到账",
                    new Metadata(Map.of(
                            KeelChunkMetadata.CHUNK_ID, "c-refund",
                            KeelChunkMetadata.SOURCE, "refund-wiki",
                            KeelChunkMetadata.TENANT_ID, "tenant-1",
                            KeelChunkMetadata.RESOURCE_ID, "doc-1")));
            store.add(embeddingModel.embed(segment).content(), segment);
            return store;
        }
    }
}
