package io.keel.guard;

import java.util.List;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.SkillDefinition;

/**
 * Ensures that skills requiring citations never return a successful result
 * without any citation.
 */
public final class CitationGuard implements AgentGuard {

    public static final String UNCERTAIN_TEXT = "没有检索依据，无法给出确定结论。";

    @Override
    public AgentResult apply(AgentRequest request, AgentResult result, SkillDefinition skill) {
        if (result == null) {
            return null;
        }
        if (skill == null || !skill.isRequireCitation()) {
            return result;
        }
        if (result.getStatus() != AgentStatus.SUCCESS || !result.getCitations().isEmpty()) {
            return result;
        }

        return AgentResult.builder()
                .status(AgentStatus.UNCERTAIN)
                .text(UNCERTAIN_TEXT)
                .citations(List.of())
                .pendingActions(List.of())
                .traceId(result.getTraceId())
                .build();
    }
}
