package io.keel.starter;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.keel.graph.Checkpoint;
import io.keel.graph.spi.CheckpointPort;

/**
 * graph + RAG / MCP 装配测试共用夹具：队列式 ChatModel + 确定性哈希 EmbeddingModel，
 * 全程不触网。
 */
final class GraphRagTestFixtures {

    private GraphRagTestFixtures() {
    }

    /**
     * 空实现的 CheckpointPort，只用来验证「业务自备实现」时校验器让位。
     * 不实现任何存储语义——它存在的意义是成为容器里的一个 bean。
     */
    static final class NoopCheckpointPort implements CheckpointPort {

        @Override
        public void save(Checkpoint checkpoint) {
            // 故意空实现：本夹具只用于装配判定，不承担持久化职责
        }

        @Override
        public java.util.Optional<Checkpoint> load(String runId) {
            return java.util.Optional.empty();
        }

        @Override
        public void delete(String runId) {
            // 故意空实现
        }
    }

    /** 按队列顺序返回脚本回复；耗尽后给兜底 ANSWER，避免图循环意外卡死。 */
    static class QueuedChatModel implements ChatModel {

        private final Deque<String> replies = new ArrayDeque<>();

        private final AtomicInteger callCount = new AtomicInteger();

        void reset(String... replies) {
            this.replies.clear();
            this.replies.addAll(Arrays.asList(replies));
            this.callCount.set(0);
        }

        int getCallCount() {
            return callCount.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount.incrementAndGet();
            String reply = replies.isEmpty()
                    ? "{\"action\":\"ANSWER\",\"answer\":\"兜底回复\"}"
                    : replies.pollFirst();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    /** 词袋哈希 + L2 归一化的确定性 EmbeddingModel（与 keel-rag 测试同款算法）。 */
    static class HashingEmbeddingModel implements EmbeddingModel {

        private static final int DIMENSION = 128;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream()
                    .map(segment -> new Embedding(vectorize(segment.text())))
                    .toList());
        }

        static float[] vectorize(String text) {
            float[] vector = new float[DIMENSION];
            String[] tokens = text.toLowerCase().split("[^\\p{L}\\p{N}]+");
            int hits = 0;
            for (String token : tokens) {
                if (!token.isEmpty()) {
                    vector[Math.floorMod(token.hashCode(), DIMENSION)] += 1.0f;
                    hits++;
                }
            }
            if (hits == 0) {
                vector[0] = 1.0f;
                hits = 1;
            }
            double norm = 0.0;
            for (float value : vector) {
                norm += value * value;
            }
            norm = Math.sqrt(norm);
            for (int i = 0; i < DIMENSION; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
            return vector;
        }
    }
}
