package io.keel.rag;

import java.util.List;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

/**
 * 测试用确定性 EmbeddingModel：词袋哈希向量 + L2 归一化。
 * 不依赖真实模型/网络，共享词汇的文本余弦相似度高，足以验证检索与 ACL 过滤逻辑。
 */
public class HashingEmbeddingModel implements EmbeddingModel {

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
            // 避免零向量导致余弦无定义
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
