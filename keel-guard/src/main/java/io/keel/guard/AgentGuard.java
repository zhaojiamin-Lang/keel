package io.keel.guard;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.SkillDefinition;

/**
 * Post-processing guard applied to an {@link AgentResult} produced by an
 * agent. Implementations must not depend on Spring AI or any web/runtime
 * infrastructure; they are pure safety rules.
 */
public interface AgentGuard {

    AgentResult apply(AgentRequest request, AgentResult result, SkillDefinition skill);
}
