package io.keel.skill;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.SkillDefinition;

class ToolCatalogTest {

    @Test
    void rejectsSkillToolThatIsNotAllowed() {
        ToolCatalog catalog = new ToolCatalog(List.of("read_ticket"));
        SkillDefinition skill = SkillDefinition.builder()
                .name("write-ticket")
                .tools(List.of("write_ticket"))
                .writable(true)
                .build();

        assertThatThrownBy(() -> catalog.validate(skill))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("write-ticket")
                .hasMessageContaining("write_ticket")
                .hasMessageContaining("read_ticket");
    }

    @Test
    void allowsSkillWithEmptyTools() {
        ToolCatalog catalog = new ToolCatalog(List.of());
        SkillDefinition skill = SkillDefinition.builder()
                .name("chat-only")
                .tools(List.of())
                .build();

        catalog.validate(skill);
    }
}
