package io.keel.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;
import io.keel.core.SkillRouter.RoutingResult;

class KeywordSkillRouterTest {

    private final KeywordSkillRouter router = new KeywordSkillRouter();

    private final SkillDefinition ticketSkill = SkillDefinition.builder()
            .name("ticket-assist")
            .description("工单创建 工单查询 工单状态")
            .build();

    private final SkillDefinition orderSkill = SkillDefinition.builder()
            .name("order-query")
            .description("订单查询 订单状态 物流跟踪")
            .build();

    @Test
    void routesToTicketSkillWhenInputMatchesKeywords() {
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t", "u", List.of()))
                .input("帮我查一下工单状态")
                .build();

        var result = router.route(request, List.of(ticketSkill, orderSkill));

        assertThat(result).isPresent();
        assertThat(result.get().skill().getName()).isEqualTo("ticket-assist");
        assertThat(result.get().confidence()).isGreaterThan(0.3);
    }

    @Test
    void routesToOrderSkillWhenInputMatchesOrderKeywords() {
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t", "u", List.of()))
                .input("查询订单状态和物流")
                .build();

        var result = router.route(request, List.of(ticketSkill, orderSkill));

        assertThat(result).isPresent();
        assertThat(result.get().skill().getName()).isEqualTo("order-query");
    }

    @Test
    void lowConfidenceFallsBackToChat() {
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t", "u", List.of()))
                .input("今天天气怎么样")
                .build();

        var result = router.route(request, List.of(ticketSkill, orderSkill));

        assertThat(result).isEmpty();
    }

    @Test
    void emptyCandidatesFallsBackToChat() {
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t", "u", List.of()))
                .input("帮我查工单")
                .build();

        var result = router.route(request, List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void picksHighestConfidenceWhenMultipleMatch() {
        SkillDefinition strongMatch = SkillDefinition.builder()
                .name("strong")
                .description("工单 工单 工单 工单")
                .build();
        SkillDefinition weakMatch = SkillDefinition.builder()
                .name("weak")
                .description("工单 订单 物流 天气")
                .build();

        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t", "u", List.of()))
                .input("查工单")
                .build();

        var result = router.route(request, List.of(strongMatch, weakMatch));

        assertThat(result).isPresent();
        assertThat(result.get().skill().getName()).isEqualTo("strong");
    }
}