package io.keel.starter;

import java.util.Objects;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.SkillDefinition;
import io.keel.guard.AgentGuard;
import io.keel.skill.SkillRegistry;

/**
 * Delegating {@link KeelAgent} that resolves the requested skill and applies
 * an {@link AgentGuard} to the produced result.
 */
public class GuardedKeelAgent implements KeelAgent {

    public static final String DEFAULT_SKILL = "chat";

    private final KeelAgent delegate;
    private final SkillRegistry skillRegistry;
    private final AgentGuard guard;

    public GuardedKeelAgent(
            KeelAgent delegate,
            SkillRegistry skillRegistry,
            AgentGuard guard) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public AgentResult run(AgentRequest request) {
        if (request == null) {
            return error("INVALID_REQUEST", "request is required");
        }

        String skillName = request.getSkill() == null || request.getSkill().isBlank()
                ? DEFAULT_SKILL
                : request.getSkill();
        SkillDefinition skill = skillRegistry.get(skillName).orElse(null);
        if (skill == null) {
            return error("UNKNOWN_SKILL", "skill not found: " + skillName);
        }

        try {
            AgentResult result = delegate.run(request);
            return guard.apply(request, result, skill);
        } catch (RuntimeException exception) {
            return AgentResult.builder()
                    .status(AgentStatus.ERROR)
                    .text("")
                    .errorCode("AGENT_ERROR")
                    .errorMessage("agent execution failed: " + exception.getMessage())
                    .build();
        }
    }

    private AgentResult error(String errorCode, String errorMessage) {
        return AgentResult.builder()
                .status(AgentStatus.ERROR)
                .text("")
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
