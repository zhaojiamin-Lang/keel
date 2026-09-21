package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

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

/**
 * keel.retrieval.enabled=false：即使 classpath 与 bean 都齐备，也不装配 RetrievalPort；
 * 图内核对检索请求 fail-closed（NO_RETRIEVAL_PORT），不允许静默跳过证据直接作答。
 */
@SpringBootTest(properties = {
        "keel.agent.mode=graph",
        "keel.retrieval.enabled=false"
})
class GraphRagDisabledAutoConfigurationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private GraphRagTestFixtures.QueuedChatModel chatModel;

    @Autowired
    private ObjectProvider<RetrievalPort> retrievalPorts;

    @BeforeEach
    void resetChat() {
        chatModel.reset(
                "{\"action\":\"RETRIEVE\",\"query\":\"退款政策\"}");
    }

    @Test
    void retrievalRequestFailsClosedWhenRagDisabled() {
        assertThat(retrievalPorts.getIfAvailable()).isNull();

        AgentResult result = keelAgent.run(request("wiki-qa", "退款多久到账"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("NO_RETRIEVAL_PORT");
    }

    private AgentRequest request(String skill, String input) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant-1", "subject", List.of("doc-1")))
                .skill(skill)
                .input(input)
                .build();
    }

    @SpringBootApplication
    static class RagDisabledTestApplication {

        @Bean
        ChatModel chatModel() {
            return new GraphRagTestFixtures.QueuedChatModel();
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return new GraphRagTestFixtures.HashingEmbeddingModel();
        }

        @Bean
        EmbeddingStore<TextSegment> embeddingStore() {
            return new InMemoryEmbeddingStore<>();
        }
    }
}
