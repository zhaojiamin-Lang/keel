package io.keel.model;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.model.ChatModel;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;

public class DirectChatKeelAgent implements KeelAgent {

    private final ChatModel chatModel;

    public DirectChatKeelAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public AgentResult run(AgentRequest request) {
        if (request == null || request.getInput() == null || request.getInput().isBlank()) {
            return AgentResult.builder()
                    .status(AgentStatus.ERROR)
                    .text("")
                    .errorCode("INVALID_REQUEST")
                    .errorMessage("request or input is required")
                    .build();
        }

        try {
            String output = chatModel.call(request.getInput());
            return AgentResult.builder()
                    .status(AgentStatus.UNCERTAIN)
                    .text(output == null ? "" : output)
                    .citations(List.of())
                    .pendingActions(List.of())
                    .traceId(UUID.randomUUID().toString())
                    .build();
        } catch (RuntimeException exception) {
            return AgentResult.builder()
                    .status(AgentStatus.ERROR)
                    .text("")
                    .errorCode("MODEL_ERROR")
                    .errorMessage("model call failed")
                    .traceId(UUID.randomUUID().toString())
                    .build();
        }
    }
}
