package io.keel.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.keel.core.AgentRequest;
import io.keel.core.SkillDefinition;
import io.keel.core.SkillRouter;

/**
 * FR-21：基于关键词的 Skill 路由器（默认实现）。
 *
 * <p>从每个 skill 的 description 中提取关键词，与用户输入做匹配。
 * 置信度 = 命中关键词数 / 该 skill 关键词总数。低于 {@link #threshold}
 * 回退 chat（返回 empty）。</p>
 *
 * <p>关键词提取：description 按非字母数字分割，去掉停用词（的、了、是、在等），
 * 剩下的词作为关键词。中文按字分割（无分词库依赖，简单覆盖）。</p>
 */
public class KeywordSkillRouter implements SkillRouter {

    /** 默认置信度阈值：低于 0.3 回退 chat */
    public static final double DEFAULT_THRESHOLD = 0.3;

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "或", "a", "an", "the",
            "is", "are", "to", "for", "of", "in", "on", "with");

    private final double threshold;

    public KeywordSkillRouter() {
        this(DEFAULT_THRESHOLD);
    }

    public KeywordSkillRouter(double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be in [0.0, 1.0]");
        }
        this.threshold = threshold;
    }

    @Override
    public Optional<RoutingResult> route(AgentRequest request, List<SkillDefinition> candidates) {
        if (request == null || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String input = request.getInput();
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalizedInput = normalize(input);

        RoutingResult best = null;
        for (SkillDefinition skill : candidates) {
            List<String> keywords = extractKeywords(skill.getDescription());
            if (keywords.isEmpty()) {
                continue;
            }
            int hits = 0;
            List<String> hitWords = new ArrayList<>();
            for (String keyword : keywords) {
                if (normalizedInput.contains(keyword)) {
                    hits++;
                    hitWords.add(keyword);
                }
            }
            double confidence = (double) hits / keywords.size();
            if (confidence >= threshold
                    && (best == null || confidence > best.confidence())) {
                best = new RoutingResult(skill, confidence,
                        "关键词命中: " + String.join(", ", hitWords));
            }
        }
        return Optional.ofNullable(best);
    }

    /** 提取关键词：description 按非字母数字（含中文）分割，去停用词 */
    private List<String> extractKeywords(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        String normalized = normalize(description);
        String[] tokens = normalized.split("[^\\p{L}\\p{N}]+");
        return List.of(tokens).stream()
                .filter(t -> !t.isEmpty())
                .filter(t -> !STOP_WORDS.contains(t))
                .filter(t -> t.length() > 1)  // 去单字噪声
                .distinct()
                .collect(Collectors.toList());
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).trim();
    }
}