package io.keel.skill;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.keel.core.SkillDefinition;

public final class ToolCatalog {

    private final Set<String> allowedTools;

    public ToolCatalog(List<String> allowedTools) {
        Set<String> tools = new LinkedHashSet<>();
        if (allowedTools != null) {
            for (String tool : allowedTools) {
                if (tool != null && !tool.isBlank()) {
                    tools.add(tool);
                }
            }
        }
        this.allowedTools = Set.copyOf(tools);
    }

    public List<String> getAllowedTools() {
        return List.copyOf(allowedTools);
    }

    public void validate(SkillDefinition skill) {
        Objects.requireNonNull(skill, "skill");

        List<String> illegalTools = skill.getTools().stream()
                .filter(tool -> !allowedTools.contains(tool))
                .toList();
        if (!illegalTools.isEmpty()) {
            throw new IllegalStateException(
                    "Skill '" + skill.getName()
                            + "' declares tool(s) not allowed: " + illegalTools
                            + "; allowed tools: " + getAllowedTools());
        }
    }

    public void validate(List<SkillDefinition> skills) {
        Objects.requireNonNull(skills, "skills");
        for (SkillDefinition skill : skills) {
            validate(skill);
        }
    }
}
